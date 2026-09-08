package com.example.tv_caller_app.calling.signaling

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

interface SignalingService {
    val events: SharedFlow<SignalingEvent>
    suspend fun initialize()
    suspend fun sendCallOffer(targetUserId: String, callerName: String, callerUsername: String, offer: String, mediaType: String = "audio")
    suspend fun sendCallAnswer(callerId: String, calleeName: String, answer: String)
    suspend fun sendIceCandidate(targetUserId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    suspend fun rejectCall(callerId: String, reason: String)
    suspend fun endCall(otherUserId: String, reason: String, duration: Long)
    suspend fun sendCameraState(targetUserId: String, enabled: Boolean)
    suspend fun notifyAnsweredElsewhere(ownUserId: String)
    suspend fun cleanup()
}

class SignalingManager(
    private val currentUserId: String,
    private val supabase: SupabaseClient
) : SignalingService {

    companion object {
        private const val TAG = "SignalingManager"
        private var instanceCounter = 0
    }
    private val json = Json { ignoreUnknownKeys = true }

    private val instanceId = ++instanceCounter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentUserContactId: String? = null
    private var signalingChannel: RealtimeChannel? = null
    private val _events = MutableSharedFlow<SignalingEvent>(replay = 0, extraBufferCapacity = 10)
    override val events: SharedFlow<SignalingEvent> = _events.asSharedFlow()
    private var isInitialized = false
    @Volatile private var isPeriodicReconnecting = false
    @Volatile private var hasBeenSubscribed = false

    override suspend fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "[$instanceId] SignalingManager already initialized — skipping duplicate init")
            return
        }

        try {
            Log.d(TAG, "[$instanceId] Initializing SignalingManager for user: $currentUserId")

            try {
                supabase.realtime.removeAllChannels()
                Log.d(TAG, "[$instanceId] Pre-init channel purge complete")
            } catch (e: Exception) {
                Log.w(TAG, "[$instanceId] Pre-init channel purge failed (non-fatal): ${e.message}")
            }

            try {
                @Serializable
                data class ContactIdRow(
                    @SerialName("contact_id") val contactId: Int
                )

                val rows = supabase.from("profiles")
                    .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("contact_id")) {
                        filter {
                            eq("id", currentUserId)
                        }
                    }
                    .decodeList<ContactIdRow>()

                currentUserContactId = rows.firstOrNull()?.contactId?.toString()
                Log.i(TAG, "User contact_id fetched: $currentUserContactId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch user contact_id, using UUID as fallback", e)
                currentUserContactId = currentUserId
            }

            signalingChannel = supabase.channel("webrtc-signaling")

            signalingChannel?.let { channel ->
                channel.broadcastFlow<JsonObject>(event = "message")
                    .onEach { messageJson ->
                        handleIncomingMessage(messageJson)
                    }
                    .launchIn(scope)

                scope.launch {
                    channel.status.collect { status ->
                        Log.d(TAG, "Signaling channel status: $status")
                        if (status == RealtimeChannel.Status.SUBSCRIBED) {
                            hasBeenSubscribed = true
                        }
                        if (hasBeenSubscribed && !isPeriodicReconnecting
                            && status == RealtimeChannel.Status.UNSUBSCRIBED) {
                            Log.w(TAG, "Signaling channel dropped — starting reconnect loop")
                            var delayMs = 3_000L
                            while (channel.status.value == RealtimeChannel.Status.UNSUBSCRIBED) {
                                Log.w(TAG, "Reconnecting in ${delayMs}ms...")
                                delay(delayMs)
                                if (channel.status.value != RealtimeChannel.Status.UNSUBSCRIBED) {
                                    Log.d(TAG, "Channel recovered while waiting — stopping reconnect loop")
                                    break
                                }
                                try {
                                    channel.subscribe()
                                    Log.i(TAG, "Signaling channel resubscribed successfully")
                                    break
                                } catch (e: Exception) {
                                    Log.e(TAG, "Resubscribe attempt failed: ${e.message}")
                                    delayMs = minOf(delayMs * 2, 30_000L)
                                }
                            }
                        }
                    }
                }

                scope.launch {
                    while (true) {
                        delay(2 * 60 * 60 * 1000L)
                        if (!isInitialized) break
                        Log.d(TAG, "Periodic reconnect — refreshing signaling channel")
                        isPeriodicReconnecting = true
                        try {
                            channel.unsubscribe()
                            delay(1_000)
                            channel.subscribe()
                            Log.i(TAG, "Periodic reconnect successful")
                        } catch (e: Exception) {
                            Log.e(TAG, "Periodic reconnect failed: ${e.message}")
                        } finally {
                            isPeriodicReconnecting = false
                        }
                    }
                }

                try {
                    Log.d(TAG, "[$instanceId] Calling channel.subscribe() — instance count: $instanceId")
                    channel.subscribe()
                    Log.i(TAG, "[$instanceId] ✅ Subscribed to shared signaling channel for user: $currentUserId")
                    _events.emit(SignalingEvent.Connected)
                } catch (e: Exception) {
                    Log.e(TAG, "[$instanceId] Initial subscribe failed: ${e.message}")
                }

                scope.launch {
                    delay(8_000L)
                    if (!hasBeenSubscribed && channel.status.value != RealtimeChannel.Status.SUBSCRIBED) {
                        Log.w(TAG, "[$instanceId] Channel not SUBSCRIBED after 8s — forcing resubscribe")
                        try {
                            channel.unsubscribe()
                            delay(1_000L)
                            channel.subscribe()
                            Log.i(TAG, "[$instanceId] Watchdog resubscribe complete")
                        } catch (e: Exception) {
                            Log.w(TAG, "[$instanceId] Watchdog resubscribe failed: ${e.message}")
                        }
                    }
                }
            }

            isInitialized = true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SignalingManager", e)
            _events.emit(SignalingEvent.Error("Failed to initialize signaling", e))
        }
    }

    override suspend fun sendCallOffer(
        targetUserId: String,
        callerName: String,
        callerUsername: String,
        offer: String,
        mediaType: String
    ) {
        try {
            Log.d(TAG, "Sending call offer to: $targetUserId")

            val message = SignalingMessage.CallOffer(
                callerId = currentUserContactId ?: currentUserId,
                callerUserId = currentUserId,
                callerName = callerName,
                callerUsername = callerUsername,
                sdp = offer,
                mediaType = mediaType
            )

            val payload = buildJsonObject {
                put("targetUserId", targetUserId)
                put("type", "call_offer")
                put("payload", json.encodeToString(message))
            }

            Log.d(TAG, "📤 Broadcasting offer to shared channel - Target: $targetUserId")

            signalingChannel?.broadcast(
                event = "message",
                message = payload
            )

            Log.i(TAG, "✅ Call offer broadcast completed")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send call offer", e)
            _events.emit(SignalingEvent.Error("Failed to send call offer", e))
        }
    }

    override suspend fun sendCallAnswer(
        callerId: String,
        calleeName: String,
        answer: String
    ) {
        try {
            Log.d(TAG, "Sending call answer to: $callerId")

            val message = SignalingMessage.CallAnswer(
                calleeId = currentUserId,
                calleeName = calleeName,
                sdp = answer
            )

            val payload = buildJsonObject {
                put("targetUserId", callerId)
                put("type", "call_answer")
                put("payload", json.encodeToString(message))
            }

            signalingChannel?.broadcast(
                event = "message",
                message = payload
            )

            Log.i(TAG, "Call answer sent to: $callerId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send call answer", e)
            _events.emit(SignalingEvent.Error("Failed to send call answer", e))
        }
    }

    override suspend fun sendIceCandidate(
        targetUserId: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?
    ) {
        try {
            val message = SignalingMessage.IceCandidate(
                candidate = candidate,
                sdpMid = sdpMid,
                sdpMLineIndex = sdpMLineIndex
            )

            val payload = buildJsonObject {
                put("targetUserId", targetUserId)
                put("type", "ice_candidate")
                put("payload", json.encodeToString(message))
            }

            signalingChannel?.broadcast(
                event = "message",
                message = payload
            )

            Log.d(TAG, "ICE candidate sent to: $targetUserId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ICE candidate", e)
        }
    }

    override suspend fun rejectCall(callerId: String, reason: String) {
        try {
            Log.d(TAG, "Rejecting call from: $callerId")

            val messageStr = json.encodeToString(SignalingMessage.CallRejected(reason = reason))
            Log.d(TAG, "Rejection payload: $messageStr")

            val payload = buildJsonObject {
                put("targetUserId", callerId)
                put("type", "call_rejected")
                put("payload", messageStr)
            }

            Log.d(TAG, "📤 Broadcasting rejection: $payload")

            signalingChannel?.broadcast(
                event = "message",
                message = payload
            )

            Log.i(TAG, "Call rejection sent to: $callerId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject call", e)
        }
    }

    override suspend fun endCall(otherUserId: String, reason: String, duration: Long) {
        try {
            Log.d(TAG, "Ending call with: $otherUserId")

            val message = SignalingMessage.CallEnded(
                reason = reason,
                duration = duration
            )

            val payload = buildJsonObject {
                put("targetUserId", otherUserId)
                put("type", "call_ended")
                put("payload", json.encodeToString(message))
            }

            signalingChannel?.broadcast(
                event = "message",
                message = payload
            )

            Log.i(TAG, "Call end sent to: $otherUserId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call", e)
        }
    }

    private fun handleIncomingMessage(messageJsonObject: JsonObject) {
        scope.launch {
            try {
                Log.d(TAG, "📨 Raw broadcast received: $messageJsonObject")

                val targetUserId = messageJsonObject["targetUserId"]?.jsonPrimitive?.contentOrNull

                if (targetUserId != null && targetUserId != currentUserId) {
                    Log.d(TAG, "⏭️ Message not for me (target: $targetUserId, me: $currentUserId) - ignoring")
                    return@launch
                }

                val messageType = messageJsonObject["type"]?.jsonPrimitive?.contentOrNull
                val messageJson = messageJsonObject["payload"]?.jsonPrimitive?.contentOrNull ?: ""

                Log.d(TAG, "📨 Type: $messageType, Payload for me: $messageJson")

                when (messageType) {
                    "call_offer" -> {
                        val msg = json.decodeFromString<SignalingMessage.CallOffer>(messageJson)
                        _events.emit(
                            SignalingEvent.IncomingCall(
                                callerId = msg.callerId,
                                callerUserId = msg.callerUserId,
                                callerName = msg.callerName,
                                callerUsername = msg.callerUsername,
                                offer = msg.sdp,
                                mediaType = msg.mediaType
                            )
                        )
                    }
                    "call_answer" -> {
                        val msg = json.decodeFromString<SignalingMessage.CallAnswer>(messageJson)
                        _events.emit(SignalingEvent.CallAnswered(answer = msg.sdp))
                    }
                    "ice_candidate" -> {
                        val msg = json.decodeFromString<SignalingMessage.IceCandidate>(messageJson)
                        _events.emit(
                            SignalingEvent.NewIceCandidate(
                                candidate = msg.candidate,
                                sdpMid = msg.sdpMid,
                                sdpMLineIndex = msg.sdpMLineIndex
                            )
                        )
                    }
                    "call_rejected" -> {
                        val msg = json.decodeFromString<SignalingMessage.CallRejected>(messageJson)
                        _events.emit(SignalingEvent.CallRejected(reason = msg.reason))
                    }
                    "call_ended" -> {
                        val msg = json.decodeFromString<SignalingMessage.CallEnded>(messageJson)
                        _events.emit(SignalingEvent.CallEnded(reason = msg.reason, duration = msg.duration))
                    }
                    "camera_state" -> {
                        val msg = json.decodeFromString<SignalingMessage.CameraState>(messageJson)
                        _events.emit(SignalingEvent.RemoteCameraStateChanged(enabled = msg.enabled))
                    }
                    "call_answered_elsewhere" -> {
                        Log.i(TAG, "Call answered on another device — dismissing")
                        _events.emit(SignalingEvent.CallAnsweredElsewhere)
                    }
                    else -> {
                        Log.w(TAG, "No/unknown type '$messageType', attempting field-based parsing: $messageJson")
                        when {
                            "callerUserId" in messageJson && "sdp" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.CallOffer>(messageJson)
                                _events.emit(SignalingEvent.IncomingCall(msg.callerId, msg.callerUserId, msg.callerName, msg.callerUsername, msg.sdp, msg.mediaType))
                            }
                            "calleeId" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.CallAnswer>(messageJson)
                                _events.emit(SignalingEvent.CallAnswered(answer = msg.sdp))
                            }
                            "candidate" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.IceCandidate>(messageJson)
                                _events.emit(SignalingEvent.NewIceCandidate(msg.candidate, msg.sdpMid, msg.sdpMLineIndex))
                            }
                            "duration" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.CallEnded>(messageJson)
                                _events.emit(SignalingEvent.CallEnded(reason = msg.reason, duration = msg.duration))
                            }
                            "reason" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.CallRejected>(messageJson)
                                _events.emit(SignalingEvent.CallRejected(reason = msg.reason))
                            }
                            else -> Log.w(TAG, "Unknown message: $messageJson")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming message", e)
                _events.emit(SignalingEvent.Error("Failed to parse signaling message", e))
            }
        }
    }

    override suspend fun sendCameraState(targetUserId: String, enabled: Boolean) {
        try {
            val message = SignalingMessage.CameraState(enabled = enabled)
            val payload = buildJsonObject {
                put("targetUserId", targetUserId)
                put("type", "camera_state")
                put("payload", json.encodeToString(message))
            }
            signalingChannel?.broadcast(event = "message", message = payload)
            Log.d(TAG, "Camera state sent to $targetUserId: enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send camera state", e)
        }
    }

    override suspend fun notifyAnsweredElsewhere(ownUserId: String) {
        try {
            val payload = buildJsonObject {
                put("targetUserId", ownUserId)
                put("type", "call_answered_elsewhere")
                put("payload", json.encodeToString(SignalingMessage.CallAnsweredElsewhere()))
            }
            signalingChannel?.broadcast(event = "message", message = payload)
            Log.d(TAG, "call_answered_elsewhere broadcast to own UUID: $ownUserId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify answered elsewhere", e)
        }
    }

    override suspend fun cleanup() {
        try {
            Log.d(TAG, "[$instanceId] Cleaning up SignalingManager")

            hasBeenSubscribed = false
            isInitialized = false

            signalingChannel?.let { supabase.realtime.removeChannel(it) }
            signalingChannel = null

            _events.emit(SignalingEvent.Disconnected)

            scope.cancel()

            Log.i(TAG, "[$instanceId] SignalingManager cleaned up")

        } catch (e: Exception) {
            Log.e(TAG, "[$instanceId] Error during cleanup", e)
        }
    }
}
