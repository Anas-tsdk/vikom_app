package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.data.datasource.CallDataSource
import com.example.tv_caller_app.domain.PendingCallData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    }


    private val _pendingIncomingCall = MutableStateFlow<PendingCallData?>(null)

    val pendingIncomingCall: StateFlow<PendingCallData?> = _pendingIncomingCall.asStateFlow()

    fun clearPendingIncomingCall() {
        _pendingIncomingCall.value = null
    }



    val currentUserId: String get() = callDataSource.getUserId() ?: ""
    val currentUserName: String get() = callDataSource.getUsername() ?: "User"



    var onIncomingCall: ((callerId: String, callerUserId: String, callerName: String, callerUsername: String, offer: String, contactId: Int?, nickname: String?, avatarUrl: String?, mediaType: String) -> Unit)? = null
    var onCallAnswered: ((answer: String) -> Unit)? = null
    var onNewIceCandidate: ((candidate: String, sdpMid: String?, sdpMLineIndex: Int?) -> Unit)? = null
    var onCallRejected: ((reason: String) -> Unit)? = null
    var onCallEnded: ((reason: String, duration: Long) -> Unit)? = null
    var onRemoteCameraStateChanged: ((enabled: Boolean) -> Unit)? = null
    var onCallAnsweredElsewhere: (() -> Unit)? = null
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

    suspend fun observeSignalingEvents() {
        callDataSource.onSignalingIncomingCall = { callerId, callerUserId, callerName, callerUsername, offer, mediaType ->
            val info = resolveCallerInfo(callerId, callerUserId, callerName)
            val resolvedCallerId = info.contactId?.toString() ?: callerId
            _pendingIncomingCall.value = PendingCallData(
                callerId = resolvedCallerId,
                callerName = info.displayName,
                nickname = info.nickname,
                avatarUrl = info.avatarUrl,
                mediaType = mediaType
            )
            onIncomingCall?.invoke(
                resolvedCallerId,
                callerUserId,
                info.displayName,
                callerUsername,
                offer,
                info.contactId,
                info.nickname,
                info.avatarUrl,
                mediaType
            )
        }
        callDataSource.onSignalingCallAnswered = { answer -> onCallAnswered?.invoke(answer) }
        callDataSource.onSignalingNewIceCandidate = { candidate, sdpMid, sdpMLineIndex ->
            onNewIceCandidate?.invoke(candidate, sdpMid, sdpMLineIndex)
        }
        callDataSource.onSignalingCallRejected = { reason -> onCallRejected?.invoke(reason) }
        callDataSource.onSignalingCallEnded = { reason, duration -> onCallEnded?.invoke(reason, duration) }
        callDataSource.onSignalingRemoteCameraStateChanged = { enabled -> onRemoteCameraStateChanged?.invoke(enabled) }
        callDataSource.onSignalingCallAnsweredElsewhere = { onCallAnsweredElsewhere?.invoke() }
        callDataSource.onSignalingError = { message, exception -> onSignalingError?.invoke(message, exception) }

        callDataSource.observeSignalingEvents()
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
    fun setVideoEnabled(enabled: Boolean) = callDataSource.setVideoEnabled(enabled)
    fun startVideoCapture() = callDataSource.startVideoCapture()
    fun stopVideoCapture() = callDataSource.stopVideoCapture()
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



    suspend fun sendCallOffer(targetUserId: String, offer: String, mediaType: String = "audio") =
        callDataSource.sendCallOffer(targetUserId, currentUserName, currentUserName, offer, mediaType)

    suspend fun sendCallAnswer(callerId: String, answer: String) =
        callDataSource.sendCallAnswer(callerId, currentUserName, answer)

    suspend fun notifyAnsweredElsewhere() =
        callDataSource.notifyAnsweredElsewhere(currentUserId)

    suspend fun rejectCall(callerId: String, reason: String) =
        callDataSource.rejectCall(callerId, reason)

    suspend fun endCall(otherUserId: String, reason: String, duration: Long) =
        callDataSource.endCall(otherUserId, reason, duration)

    suspend fun sendIceCandidate(targetUserId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) =
        callDataSource.sendIceCandidate(targetUserId, candidate, sdpMid, sdpMLineIndex)

    suspend fun sendCameraState(targetUserId: String, enabled: Boolean) =
        callDataSource.sendCameraState(targetUserId, enabled)

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



    fun startCallAudio() = callDataSource.startCallAudio()
    fun stopCallAudio() = callDataSource.stopCallAudio()



    fun startCallNotification(contactName: String, isIncoming: Boolean) =
        callDataSource.startCallNotification(contactName, isIncoming)

    fun stopCallNotification() = callDataSource.stopCallNotification()

    fun updateCallNotificationDuration(durationSeconds: Long, isMuted: Boolean, isSpeakerOn: Boolean) =
        callDataSource.updateCallNotificationDuration(durationSeconds, isMuted, isSpeakerOn)


}
