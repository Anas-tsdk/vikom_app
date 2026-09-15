package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.data.datasource.CallDataSource
import com.example.tv_caller_app.domain.IncomingCallOffer
import com.example.tv_caller_app.domain.PendingCallData
import com.example.tv_caller_app.domain.AppointmentEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

typealias CallConnectionState = CallDataSource.CallConnectionState

class CallRepository(
    private val callDataSource: CallDataSource,
    private val presenceRepository: PresenceRepository,
    private val contactRepository: ContactRepository,
    private val callHistoryRepository: CallHistoryRepository,
    private val quickDialRepository: QuickDialRepository
) {

    companion object {
        private const val TAG = "CallRepository"
        private const val OFFER_DEDUPE_WINDOW_MS = 2 * 60 * 1000L
        private const val MAX_REMEMBERED_OFFERS = 32
    }


    private val _pendingIncomingCall = MutableStateFlow<PendingCallData?>(null)

    val pendingIncomingCall: StateFlow<PendingCallData?> = _pendingIncomingCall.asStateFlow()

    private val _appointmentEvents = MutableSharedFlow<AppointmentEvent>(extraBufferCapacity = 8)
    val appointmentEvents: SharedFlow<AppointmentEvent> = _appointmentEvents

    private val incomingOfferLock = Any()
    private val recentlyHandledOffers = LinkedHashMap<String, Long>()

    fun clearPendingIncomingCall() {
        _pendingIncomingCall.value = null
    }



    val currentUserId: String get() = callDataSource.getUserId() ?: ""
    val currentUserName: String get() = callDataSource.getUsername() ?: "User"



    var onIncomingCall: ((callId: String, callerId: String, callerUserId: String, callerName: String, callerUsername: String, offer: String, contactId: Int?, nickname: String?, avatarUrl: String?, mediaType: String) -> Unit)? = null
    var onCallAnswered: ((callId: String, answer: String) -> Unit)? = null
    var onNewIceCandidate: ((callId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) -> Unit)? = null
    var onCallRejected: ((callId: String, reason: String) -> Unit)? = null
    var onCallEnded: ((callId: String, reason: String, duration: Long) -> Unit)? = null
    var onRemoteCameraStateChanged: ((callId: String, enabled: Boolean) -> Unit)? = null
    var onVideoUpgradeRequested: ((callId: String) -> Unit)? = null
    var onVideoUpgradeResponded: ((callId: String, accepted: Boolean) -> Unit)? = null
    var onMissedCall: ((callId: String, callerName: String, mediaType: String, timestamp: Long) -> Unit)? = null
    var onCallAnsweredElsewhere: ((callId: String) -> Unit)? = null
    var onSignalingError: ((message: String, exception: Throwable?) -> Unit)? = null



    fun setLocalIceCandidateCallback(callback: ((sdp: String, sdpMid: String?, sdpMLineIndex: Int) -> Unit)?) {
        callDataSource.setLocalIceCandidateCallback(callback)
    }

    fun setConnectionStateCallback(callback: ((CallConnectionState) -> Unit)?) {
        callDataSource.setConnectionStateCallback(callback)
    }

    fun setMicrophoneAvailabilityCallback(callback: ((isAvailable: Boolean, message: String) -> Unit)?) {
        callDataSource.setMicrophoneAvailabilityCallback(callback)
    }

    var onLocalVideoActive: ((Boolean) -> Unit)? = null

    var onRemoteVideoActive: ((Boolean) -> Unit)? = null



    fun initVideoRenderer(renderer: Any?, mirror: Boolean) = callDataSource.initVideoRenderer(renderer, mirror)
    fun attachLocalVideo(renderer: Any?) = callDataSource.attachLocalVideo(renderer)
    fun attachRemoteVideo(renderer: Any?) = callDataSource.attachRemoteVideo(renderer)
    fun detachLocalVideo(renderer: Any?) = callDataSource.detachLocalVideo(renderer)
    fun detachRemoteVideo(renderer: Any?) = callDataSource.detachRemoteVideo(renderer)



    private val pendingRemoteCandidates = mutableListOf<Triple<String, String?, Int>>()
    private var isRemoteDescriptionSet = false

    fun addOrQueueRemoteIceCandidate(candidateSdp: String, sdpMid: String?, sdpMLineIndex: Int?) {
        if (isRemoteDescriptionSet) {
            callDataSource.addIceCandidate(candidateSdp, sdpMid, sdpMLineIndex)
        } else {
            pendingRemoteCandidates.add(Triple(candidateSdp, sdpMid, sdpMLineIndex ?: 0))
            Log.d(TAG, "Queued remote ICE candidate (${pendingRemoteCandidates.size} pending)")
        }
    }

    private fun flushPendingCandidates() {
        pendingRemoteCandidates.forEach { (sdp, mid, index) ->
            callDataSource.addIceCandidate(sdp, mid, index)
        }
        Log.d(TAG, "Flushed ${pendingRemoteCandidates.size} pending ICE candidates")
        pendingRemoteCandidates.clear()
    }



    suspend fun initializeSignaling() = callDataSource.initializeSignaling()

    suspend fun refreshTurnConfiguration(): Boolean =
        callDataSource.refreshTurnConfiguration()

    suspend fun observeSignalingEvents() {
        callDataSource.onSignalingIncomingCall = { callId, callerId, callerUserId, callerName, callerUsername, offer, mediaType ->
            handleIncomingOffer(
                callId = callId,
                offer = IncomingCallOffer(
                    callerId = callerId,
                    callerUserId = callerUserId,
                    callerName = callerName,
                    callerUsername = callerUsername,
                    offerSdp = offer,
                    contactId = callerId.toIntOrNull(),
                    mediaType = mediaType
                ),
                source = "realtime"
            )
        }
        callDataSource.onSignalingCallAnswered = { callId, answer -> onCallAnswered?.invoke(callId, answer) }
        callDataSource.onSignalingNewIceCandidate = { callId, candidate, sdpMid, sdpMLineIndex ->
            onNewIceCandidate?.invoke(callId, candidate, sdpMid, sdpMLineIndex)
        }
        callDataSource.onSignalingCallRejected = { callId, reason -> onCallRejected?.invoke(callId, reason) }
        callDataSource.onSignalingCallEnded = { callId, reason, duration -> onCallEnded?.invoke(callId, reason, duration) }
        callDataSource.onSignalingRemoteCameraStateChanged = { callId, enabled -> onRemoteCameraStateChanged?.invoke(callId, enabled) }
        callDataSource.onSignalingVideoUpgradeRequested = { callId -> onVideoUpgradeRequested?.invoke(callId) }
        callDataSource.onSignalingVideoUpgradeResponded = { callId, accepted -> onVideoUpgradeResponded?.invoke(callId, accepted) }
        callDataSource.onSignalingMissedCall = { callId, callerName, mediaType, timestamp ->
            onMissedCall?.invoke(callId, callerName, mediaType, timestamp)
        }
        callDataSource.onSignalingCallAnsweredElsewhere = { callId -> onCallAnsweredElsewhere?.invoke(callId) }
        callDataSource.onSignalingError = { message, exception -> onSignalingError?.invoke(message, exception) }
        callDataSource.onAppointmentChanged = { appointment ->
            _appointmentEvents.tryEmit(appointment)
        }

        callDataSource.observeSignalingEvents()
    }

    /**
     * Injects a data-only push offer into the same path used by realtime.
     * Returns false when the offer was a duplicate or the call stack was not
     * ready. The dedupe window prevents realtime + FCM delivery of one offer
     * from being interpreted as a second, busy call.
     */
    suspend fun injectIncomingCallOffer(offer: IncomingCallOffer): Boolean {
        try {
            callDataSource.recheckMicrophoneAvailability()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to refresh microphone availability for push call", error)
        }
        return handleIncomingOffer(offer, source = "fcm", callId = offer.callId)
    }

    private suspend fun handleIncomingOffer(offer: IncomingCallOffer, source: String, callId: String = ""): Boolean {
        if (onIncomingCall == null) {
            Log.w(TAG, "Ignoring $source incoming offer because call callbacks are not ready")
            return false
        }

        val fingerprint = offerFingerprint(offer.callerUserId, offer.offerSdp)
        if (!claimIncomingOffer(fingerprint)) {
            Log.i(TAG, "Ignoring duplicate incoming offer delivered by $source")
            return false
        }

        return try {
            val info = resolveCallerInfo(
                callerId = offer.contactId?.toString() ?: offer.callerId,
                callerUserId = offer.callerUserId,
                callerName = offer.callerName
            )
            val resolvedCallerId = info.contactId?.toString()
                ?: offer.contactId?.toString()
                ?: offer.callerId
            val displayName = info.displayName.ifBlank { offer.callerName }
            val nickname = info.nickname ?: offer.nickname
            val avatarUrl = info.avatarUrl ?: offer.avatarUrl
            val normalizedMediaType = if (offer.mediaType.equals("video", ignoreCase = true)) {
                "video"
            } else {
                "audio"
            }

            _pendingIncomingCall.value = PendingCallData(
                callerId = resolvedCallerId,
                callerName = displayName,
                nickname = nickname,
                avatarUrl = avatarUrl,
                mediaType = normalizedMediaType
            )
            onIncomingCall?.invoke(
                callId,
                resolvedCallerId,
                offer.callerUserId,
                displayName,
                offer.callerUsername,
                offer.offerSdp,
                info.contactId ?: offer.contactId,
                nickname,
                avatarUrl,
                normalizedMediaType
            )
            Log.i(TAG, "Accepted incoming $normalizedMediaType offer from $source")
            true
        } catch (error: Exception) {
            synchronized(incomingOfferLock) { recentlyHandledOffers.remove(fingerprint) }
            Log.e(TAG, "Failed to inject incoming offer from $source", error)
            false
        }
    }

    private fun claimIncomingOffer(fingerprint: String): Boolean = synchronized(incomingOfferLock) {
        val now = System.currentTimeMillis()
        recentlyHandledOffers.entries.removeAll { now - it.value > OFFER_DEDUPE_WINDOW_MS }
        if (recentlyHandledOffers.containsKey(fingerprint)) return@synchronized false
        recentlyHandledOffers[fingerprint] = now
        while (recentlyHandledOffers.size > MAX_REMEMBERED_OFFERS) {
            val oldest = recentlyHandledOffers.entries.firstOrNull()?.key ?: break
            recentlyHandledOffers.remove(oldest)
        }
        true
    }

    private fun offerFingerprint(callerUserId: String, offerSdp: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$callerUserId\n${offerSdp.trim()}".toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private data class CallerInfo(val contactId: Int?, val displayName: String, val nickname: String?, val avatarUrl: String?)

    private suspend fun resolveCallerInfo(callerId: String, callerUserId: String, callerName: String): CallerInfo {
        var contactId = callerId.toIntOrNull()
        var displayName = callerName.takeIf { it != "Unknown Caller" } ?: "Unknown Caller"
        var nickname: String? = null
        var avatarUrl: String? = null

        try {
            val contact = if (contactId != null) {
                contactRepository.getContactByContactId(contactId)
            } else {
                Log.d(TAG, "callerId is UUID, resolving profile for: $callerUserId")
                val presence = presenceRepository.getUserPresence(callerUserId).getOrNull()
                if (presence != null) {
                    contactId = presence.contactId
                    contactRepository.getContactByContactId(presence.contactId)
                } else null
            }
            if (contact != null) {
                displayName = contact.username
                nickname = contact.nickname
                avatarUrl = contact.avatarUrl
                Log.d(TAG, "Resolved caller: ${contact.username} (nickname=$nickname)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve caller info", e)
        }

        return CallerInfo(contactId, displayName, nickname, avatarUrl)
    }



    fun initializeWebRTC() {
        callDataSource.initializeWebRTC(
            onLocalVideoActive = { active -> onLocalVideoActive?.invoke(active) },
            onRemoteVideoActive = { active -> onRemoteVideoActive?.invoke(active) }
        )
    }

    fun hasCamera(): Boolean = callDataSource.hasCamera()
    fun hasMultipleCameras(): Boolean = callDataSource.hasMultipleCameras()
    fun recheckMicrophoneAvailability() = callDataSource.recheckMicrophoneAvailability()
    fun setVideoEnabled(enabled: Boolean) = callDataSource.setVideoEnabled(enabled)
    fun startVideoCapture() = callDataSource.startVideoCapture()
    fun stopVideoCapture() = callDataSource.stopVideoCapture()
    fun switchCamera(onComplete: (success: Boolean, frontFacing: Boolean) -> Unit) =
        callDataSource.switchCamera(onComplete)
    fun mute() = callDataSource.mute()
    fun unmute() = callDataSource.unmute()
    fun enableSpeaker() = callDataSource.enableSpeaker()
    fun disableSpeaker() = callDataSource.disableSpeaker()
    suspend fun createOffer(): String = callDataSource.createOffer()

    suspend fun createAnswer(offerSdp: String): String {
        val answer = callDataSource.createAnswer(offerSdp)
        isRemoteDescriptionSet = true
        flushPendingCandidates()
        return answer
    }

    suspend fun handleAnswer(answerSdp: String): Result<Unit> {
        val result = callDataSource.handleAnswer(answerSdp)
        if (result.isSuccess) {
            isRemoteDescriptionSet = true
            flushPendingCandidates()
        }
        return result
    }

    suspend fun closeWebRTC() {
        callDataSource.closeWebRTC()
        callDataSource.clearVideoTracks()
        pendingRemoteCandidates.clear()
        isRemoteDescriptionSet = false
    }

    fun cleanupWebRTC() {
        callDataSource.cleanupWebRTC()
        callDataSource.clearVideoTracks()
        pendingRemoteCandidates.clear()
        isRemoteDescriptionSet = false
    }



    suspend fun sendCallOffer(callId: String, targetUserId: String, offer: String, mediaType: String = "audio") =
        callDataSource.sendCallOffer(callId, targetUserId, currentUserName, currentUserName, offer, mediaType)

    suspend fun sendCallAnswer(callId: String, callerId: String, answer: String) =
        callDataSource.sendCallAnswer(callId, callerId, currentUserName, answer)

    suspend fun notifyAnsweredElsewhere(callId: String) =
        callDataSource.notifyAnsweredElsewhere(callId, currentUserId)

    suspend fun rejectCall(callId: String, callerId: String, reason: String) =
        callDataSource.rejectCall(callId, callerId, reason)

    suspend fun endCall(callId: String, otherUserId: String, reason: String, duration: Long) =
        callDataSource.endCall(callId, otherUserId, reason, duration)

    suspend fun sendIceCandidate(callId: String, targetUserId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) =
        callDataSource.sendIceCandidate(callId, targetUserId, candidate, sdpMid, sdpMLineIndex)

    suspend fun sendCameraState(callId: String, targetUserId: String, enabled: Boolean) =
        callDataSource.sendCameraState(callId, targetUserId, enabled)

    suspend fun requestVideoUpgrade(callId: String, targetUserId: String) =
        callDataSource.requestVideoUpgrade(callId, targetUserId)

    suspend fun respondToVideoUpgrade(callId: String, targetUserId: String, accepted: Boolean) =
        callDataSource.respondToVideoUpgrade(callId, targetUserId, accepted)

    suspend fun cleanupSignaling() = callDataSource.cleanupSignaling()



    suspend fun updateWebRTCStatus(status: String) = presenceRepository.updateWebRTCStatus(status)



    suspend fun logCallToHistory(
        callType: String,
        duration: Long,
        endReason: String,
        remoteUserName: String?,
        remoteUserContactId: Int?
    ) {
        try {
            callHistoryRepository.logCall(
                contactId = remoteUserContactId,
                contactName = remoteUserName,
                callType = callType,
                callDuration = duration.toInt(),
                endReason = endReason
            )
            quickDialRepository.invalidateCache()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log call to history", e)
        }
    }



    fun startCallAudio(preferSpeaker: Boolean = false) = callDataSource.startCallAudio(preferSpeaker)
    fun stopCallAudio() = callDataSource.stopCallAudio()



    fun startCallNotification(contactName: String, isIncoming: Boolean, mediaType: String) =
        callDataSource.startCallNotification(contactName, isIncoming, mediaType)

    fun stopCallNotification() = callDataSource.stopCallNotification()

    fun updateCallNotificationDuration(durationSeconds: Long, isMuted: Boolean, isSpeakerOn: Boolean) =
        callDataSource.updateCallNotificationDuration(durationSeconds, isMuted, isSpeakerOn)


}
