package com.example.tv_caller_app.calling.signaling

import android.content.Context
import android.util.Log
import com.example.tv_caller_app.BuildConfig
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.calling.service.MissedCallNotificationManager
import com.example.tv_caller_app.domain.AppointmentEvent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface SignalingService {
    val events: SharedFlow<SignalingEvent>
    suspend fun initialize()
    suspend fun refreshTurnConfiguration(): Boolean
    suspend fun sendCallOffer(callId: String, targetUserId: String, callerName: String, callerUsername: String, offer: String, mediaType: String = "audio")
    suspend fun sendCallAnswer(callId: String, callerId: String, calleeName: String, answer: String)
    suspend fun sendIceCandidate(callId: String, targetUserId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
    suspend fun rejectCall(callId: String, callerId: String, reason: String)
    suspend fun endCall(callId: String, otherUserId: String, reason: String, duration: Long)
    suspend fun sendCameraState(callId: String, targetUserId: String, enabled: Boolean)
    suspend fun requestVideoUpgrade(callId: String, targetUserId: String)
    suspend fun respondToVideoUpgrade(callId: String, targetUserId: String, accepted: Boolean)
    suspend fun notifyAnsweredElsewhere(callId: String, ownUserId: String)
    suspend fun cleanup()
}

class SignalingManager(
    private val currentUserId: String,
    private val supabase: SupabaseClient,
    context: Context? = null
) : SignalingService {

    companion object {
        private const val TAG = "SignalingManager"
        private var instanceCounter = 0
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val applicationContext = context?.applicationContext
    private val durableTransport = DurableCallTransport(supabase) {
        applicationContext?.let { SessionManager.getInstance(it).getAccessToken() }
            ?: supabase.auth.currentSessionOrNull()?.accessToken
    }
    private val handledMessageKeys = LinkedHashSet<String>()
    private val pendingDurableOffers = ConcurrentHashMap<String, Long>()
    private val completedIncomingCallIds = ConcurrentHashMap.newKeySet<String>()

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

            // Fetch short-lived TURN credentials before a peer connection is
            // created. The development relay remains available if this optional
            // production infrastructure has not been deployed yet.
            refreshTurnConfiguration()

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
            startDurablePolling()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SignalingManager", e)
            _events.emit(SignalingEvent.Error("Failed to initialize signaling", e))
        }
    }

    /**
     * Fetch fresh short-lived relay credentials immediately before a call.
     * Loading them only at app startup makes long-running tablet installations
     * silently lose cross-network calling when those credentials expire.
     */
    override suspend fun refreshTurnConfiguration(): Boolean =
        durableTransport.refreshTurnConfiguration()

    override suspend fun sendCallOffer(
        callId: String,
        targetUserId: String,
        callerName: String,
        callerUsername: String,
        offer: String,
        mediaType: String
    ) {
        try {
            Log.d(TAG, "Sending call offer to: $targetUserId")

            val message = SignalingMessage.CallOffer(
                callId = callId,
                callerId = currentUserContactId ?: currentUserId,
                callerUserId = currentUserId,
                callerName = callerName,
                callerUsername = callerUsername,
                sdp = offer,
                mediaType = mediaType
            )

            deliver(targetUserId, "call_offer", json.encodeToString(message))
            Log.i(TAG, "Call offer delivered")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send call offer", e)
            _events.emit(SignalingEvent.Error("Failed to send call offer", e))
            throw e
        }
    }

    override suspend fun sendCallAnswer(
        callId: String,
        callerId: String,
        calleeName: String,
        answer: String
    ) {
        try {
            Log.d(TAG, "Sending call answer to: $callerId")

            val message = SignalingMessage.CallAnswer(
                callId = callId,
                calleeId = currentUserId,
                calleeName = calleeName,
                sdp = answer
            )

            deliver(callerId, "call_answer", json.encodeToString(message))
            completedIncomingCallIds += callId
            acknowledgePendingOffer(callId)

            Log.i(TAG, "Call answer sent to: $callerId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send call answer", e)
            _events.emit(SignalingEvent.Error("Failed to send call answer", e))
            throw e
        }
    }

    override suspend fun sendIceCandidate(
        callId: String,
        targetUserId: String,
        candidate: String,
        sdpMid: String?,
        sdpMLineIndex: Int?
    ) {
        try {
            val message = SignalingMessage.IceCandidate(
                callId = callId,
                candidate = candidate,
                sdpMid = sdpMid,
                sdpMLineIndex = sdpMLineIndex
            )

            deliver(targetUserId, "ice_candidate", json.encodeToString(message))

            Log.d(TAG, "ICE candidate sent to: $targetUserId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ICE candidate", e)
        }
    }

    override suspend fun rejectCall(callId: String, callerId: String, reason: String) {
        try {
            Log.d(TAG, "Rejecting call from: $callerId")

            val messageStr = json.encodeToString(SignalingMessage.CallRejected(callId = callId, reason = reason))
            Log.d(TAG, "Rejection payload: $messageStr")

            deliver(callerId, "call_rejected", messageStr)
            completedIncomingCallIds += callId
            acknowledgePendingOffer(callId)

            Log.i(TAG, "Call rejection sent to: $callerId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to reject call", e)
        }
    }

    override suspend fun endCall(callId: String, otherUserId: String, reason: String, duration: Long) {
        try {
            Log.d(TAG, "Ending call with: $otherUserId")

            val message = SignalingMessage.CallEnded(
                callId = callId,
                reason = reason,
                duration = duration
            )

            deliver(otherUserId, "call_ended", json.encodeToString(message))

            Log.i(TAG, "Call end sent to: $otherUserId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call", e)
        }
    }

    private suspend fun handleIncomingMessage(messageJsonObject: JsonObject) {
        try {
                val targetUserId = messageJsonObject["targetUserId"]?.jsonPrimitive?.contentOrNull

                if (targetUserId != currentUserId) {
                    Log.d(TAG, "⏭️ Message not for me (target: $targetUserId, me: $currentUserId) - ignoring")
                    return
                }

                val messageType = messageJsonObject["type"]?.jsonPrimitive?.contentOrNull
                val messageJson = messageJsonObject["payload"]?.jsonPrimitive?.contentOrNull ?: ""

                if (messageType.isNullOrBlank() || !claimMessage(messageType, messageJson)) {
                    return
                }

                Log.d(TAG, "📨 Signaling message for this user: type=$messageType")

                when (messageType) {
                    "call_offer" -> {
                        val msg = json.decodeFromString<SignalingMessage.CallOffer>(messageJson)
                        _events.emit(
                            SignalingEvent.IncomingCall(
                                callId = msg.callId,
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
                        _events.emit(SignalingEvent.CallAnswered(callId = msg.callId, answer = msg.sdp))
                    }
                    "ice_candidate" -> {
                        val msg = json.decodeFromString<SignalingMessage.IceCandidate>(messageJson)
                        _events.emit(
                            SignalingEvent.NewIceCandidate(
                                callId = msg.callId,
                                candidate = msg.candidate,
                                sdpMid = msg.sdpMid,
                                sdpMLineIndex = msg.sdpMLineIndex
                            )
                        )
                    }
                    "call_rejected" -> {
                        val msg = json.decodeFromString<SignalingMessage.CallRejected>(messageJson)
                        _events.emit(SignalingEvent.CallRejected(callId = msg.callId, reason = msg.reason))
                    }
                    "call_ended" -> {
                        val msg = json.decodeFromString<SignalingMessage.CallEnded>(messageJson)
                        _events.emit(SignalingEvent.CallEnded(callId = msg.callId, reason = msg.reason, duration = msg.duration))
                    }
                    "camera_state" -> {
                        val msg = json.decodeFromString<SignalingMessage.CameraState>(messageJson)
                        _events.emit(SignalingEvent.RemoteCameraStateChanged(callId = msg.callId, enabled = msg.enabled))
                    }
                    "video_upgrade_request" -> {
                        val msg = json.decodeFromString<SignalingMessage.VideoUpgradeRequest>(messageJson)
                        _events.emit(SignalingEvent.VideoUpgradeRequested(msg.callId))
                    }
                    "video_upgrade_response" -> {
                        val msg = json.decodeFromString<SignalingMessage.VideoUpgradeResponse>(messageJson)
                        _events.emit(SignalingEvent.VideoUpgradeResponded(msg.callId, msg.accepted))
                    }
                    "call_answered_elsewhere" -> {
                        val msg = json.decodeFromString<SignalingMessage.CallAnsweredElsewhere>(messageJson)
                        Log.i(TAG, "Call answered on another device — dismissing")
                        _events.emit(SignalingEvent.CallAnsweredElsewhere(msg.callId))
                    }
                    "appointment_event" -> {
                        val appointment = json.decodeFromString<AppointmentEvent>(messageJson)
                        _events.emit(SignalingEvent.AppointmentChanged(appointment))
                    }
                    else -> {
                        Log.w(TAG, "No/unknown type '$messageType', attempting field-based parsing: $messageJson")
                        when {
                            "callerUserId" in messageJson && "sdp" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.CallOffer>(messageJson)
                                _events.emit(SignalingEvent.IncomingCall(msg.callId, msg.callerId, msg.callerUserId, msg.callerName, msg.callerUsername, msg.sdp, msg.mediaType))
                            }
                            "calleeId" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.CallAnswer>(messageJson)
                                _events.emit(SignalingEvent.CallAnswered(msg.callId, answer = msg.sdp))
                            }
                            "candidate" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.IceCandidate>(messageJson)
                                _events.emit(SignalingEvent.NewIceCandidate(msg.callId, msg.candidate, msg.sdpMid, msg.sdpMLineIndex))
                            }
                            "duration" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.CallEnded>(messageJson)
                                _events.emit(SignalingEvent.CallEnded(msg.callId, reason = msg.reason, duration = msg.duration))
                            }
                            "reason" in messageJson -> {
                                val msg = json.decodeFromString<SignalingMessage.CallRejected>(messageJson)
                                _events.emit(SignalingEvent.CallRejected(msg.callId, reason = msg.reason))
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

    override suspend fun sendCameraState(callId: String, targetUserId: String, enabled: Boolean) {
        try {
            val message = SignalingMessage.CameraState(callId = callId, enabled = enabled)
            deliver(targetUserId, "camera_state", json.encodeToString(message))
            Log.d(TAG, "Camera state sent to $targetUserId: enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send camera state", e)
        }
    }

    override suspend fun requestVideoUpgrade(callId: String, targetUserId: String) {
        sendControlMessage(
            targetUserId = targetUserId,
            type = "video_upgrade_request",
            encodedPayload = json.encodeToString(SignalingMessage.VideoUpgradeRequest(callId))
        )
    }

    override suspend fun respondToVideoUpgrade(callId: String, targetUserId: String, accepted: Boolean) {
        sendControlMessage(
            targetUserId = targetUserId,
            type = "video_upgrade_response",
            encodedPayload = json.encodeToString(SignalingMessage.VideoUpgradeResponse(callId, accepted))
        )
    }

    private suspend fun sendControlMessage(
        targetUserId: String,
        type: String,
        encodedPayload: String
    ) {
        deliver(targetUserId, type, encodedPayload)
        Log.d(TAG, "$type sent to $targetUserId")
    }

    override suspend fun notifyAnsweredElsewhere(callId: String, ownUserId: String) {
        try {
            // This is intentionally realtime-only. A durable event addressed
            // to the same account would be consumed by the answering device
            // before another signed-in device could dismiss its ringing UI.
            val payload = buildJsonObject {
                put("targetUserId", ownUserId)
                put("type", "call_answered_elsewhere")
                put("payload", json.encodeToString(SignalingMessage.CallAnsweredElsewhere(callId)))
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

    private fun requireSubscribedChannel(): RealtimeChannel {
        val channel = signalingChannel
            ?: throw IllegalStateException("Signaling channel has not been initialized")
        if (channel.status.value != RealtimeChannel.Status.SUBSCRIBED) {
            throw IllegalStateException("Signaling channel is not connected (${channel.status.value})")
        }
        return channel
    }

    private suspend fun deliver(
        targetUserId: String,
        type: String,
        encodedPayload: String
    ) {
        val durableSent = durableTransport.send(targetUserId, type, encodedPayload)
        if (durableSent) return
        if (!BuildConfig.DEBUG) {
            throw IllegalStateException("Secure call signaling is unavailable")
        }
        // Debug builds can still exercise calls before the Edge Function has
        // been deployed. Release builds must never expose SDP/ICE on the
        // legacy shared broadcast channel.
        val wrapper = buildJsonObject {
            put("targetUserId", targetUserId)
            put("type", type)
            put("payload", encodedPayload)
        }
        val realtimeSent = runCatching {
            requireSubscribedChannel().broadcast(event = "message", message = wrapper)
        }.onFailure { error ->
            Log.w(TAG, "Realtime $type delivery unavailable: ${error.message}")
        }.isSuccess
        if (!realtimeSent) {
            throw IllegalStateException("No signaling transport is connected")
        }
    }

    private fun claimMessage(type: String, payload: String): Boolean = synchronized(handledMessageKeys) {
        val key = "$type\n$payload"
        if (!handledMessageKeys.add(key)) return@synchronized false
        while (handledMessageKeys.size > 512) {
            val oldest = handledMessageKeys.firstOrNull() ?: break
            handledMessageKeys.remove(oldest)
        }
        true
    }

    private fun startDurablePolling() {
        scope.launch {
            while (isInitialized) {
                val rows = durableTransport.poll()
                val acknowledged = mutableListOf<Long>()
                for (row in rows) {
                    try {
                        if (row.eventType == "call_offer") {
                            val offer = json.decodeFromJsonElement(
                                SignalingMessage.CallOffer.serializer(),
                                row.payload
                            )
                            val offerCallId = offer.callId.ifBlank { row.callId }
                            if (offerCallId in completedIncomingCallIds) {
                                Log.d(TAG, "Acknowledging completed durable offer ${row.id} for $offerCallId")
                                acknowledged += row.id
                            } else if (isExpiredOffer(row.createdAt, row.id)) {
                                Log.i(TAG, "Treating durable offer ${row.id} for $offerCallId as a missed call")
                                if (claimMessage("missed_call", offerCallId)) {
                                    _events.emit(
                                        SignalingEvent.MissedCall(
                                            callId = offerCallId,
                                            callerName = offer.callerName,
                                            mediaType = offer.mediaType,
                                            timestamp = offer.timestamp
                                        )
                                    )
                                    applicationContext?.let { context ->
                                        MissedCallNotificationManager.show(
                                            context,
                                            offerCallId,
                                            offer.callerName,
                                            offer.mediaType
                                        )
                                    }
                                }
                                pendingDurableOffers.remove(offerCallId)
                                acknowledged += row.id
                            } else {
                                // Polling deliberately leaves an unanswered offer in the
                                // database so an app restart can restore the ringing screen.
                                // Only emit it once per process; otherwise every 250 ms poll
                                // races a new handler coroutine for the same offer.
                                val firstDelivery = pendingDurableOffers.putIfAbsent(offerCallId, row.id) == null
                                if (firstDelivery) {
                                    Log.i(TAG, "Delivering pending durable offer ${row.id} for $offerCallId")
                                    val wrapper = buildJsonObject {
                                        put("targetUserId", currentUserId)
                                        put("type", row.eventType)
                                        put("payload", row.payload.toString())
                                    }
                                    handleIncomingMessage(wrapper)
                                }
                                // Keep the offer pending until answer/reject so
                                // a process restart can restore the ringing UI.
                            }
                        } else {
                            val wrapper = buildJsonObject {
                                put("targetUserId", currentUserId)
                                put("type", row.eventType)
                                put("payload", row.payload.toString())
                            }
                            handleIncomingMessage(wrapper)
                            acknowledged += row.id
                            if (row.eventType == "call_ended" || row.eventType == "call_rejected") {
                                pendingDurableOffers.remove(row.callId)?.let(acknowledged::add)
                            }
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "Skipping malformed durable call event ${row.id}", error)
                        acknowledged += row.id
                    }
                }
                durableTransport.acknowledge(acknowledged)
                delay(if (rows.isEmpty()) 1_500L else 250L)
            }
        }
    }

    private fun isExpiredOffer(createdAt: String, eventId: Long): Boolean {
        val ageMs = runCatching {
            // Supabase/Postgres emits UTC timestamps with a `+00:00` suffix.
            // The java.time backport used by Android 7 rejects that otherwise
            // valid Instant form, but accepts the equivalent trailing `Z`.
            val androidCompatibleTimestamp = if (createdAt.endsWith("+00:00")) {
                createdAt.dropLast(6) + "Z"
            } else {
                createdAt
            }
            System.currentTimeMillis() - Instant.parse(androidCompatibleTimestamp).toEpochMilli()
        }.onFailure { error ->
            // A server timestamp that an old Android runtime cannot parse must
            // not silently discard a live call. The database expiry still
            // bounds how long the row can remain pending.
            Log.w(TAG, "Could not parse durable offer $eventId timestamp '$createdAt'", error)
        }.getOrNull() ?: return false
        Log.d(TAG, "Durable offer $eventId age=${ageMs}ms")
        return ageMs > 60_000L
    }

    private suspend fun acknowledgePendingOffer(callId: String) {
        pendingDurableOffers.remove(callId)?.let { durableTransport.acknowledge(listOf(it)) }
    }
}
