package com.example.tv_caller_app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.tv_caller_app.data.repository.CallConnectionState
import com.example.tv_caller_app.data.repository.CallRepository

class CallViewModel : ViewModel() {

    companion object {
        private const val TAG = "CallViewModel"
        const val RINGING_TIMEOUT_MS = 60_000L
        private const val CONNECTING_TIMEOUT_MS = 30_000L
        private const val DISCONNECT_GRACE_MS = 10_000L
    }

    private var callingRepository: CallRepository? = null

    sealed class CallState {
        data object Idle : CallState()
        data class Initiating(val targetUserId: String, val contactName: String, val nickname: String? = null, val avatarUrl: String? = null) : CallState()
        data class RingingOutgoing(val targetUserId: String, val contactName: String, val nickname: String? = null, val avatarUrl: String? = null, val callType: String = "audio") : CallState()
        data class RingingIncoming(val callerId: String, val callerName: String, val callerUsername: String, val nickname: String? = null, val avatarUrl: String? = null, val callType: String = "audio") : CallState()
        data object Connecting : CallState()
        data class Connected(val remoteUserId: String, val remoteUserName: String, val isIncoming: Boolean, val nickname: String? = null, val avatarUrl: String? = null) : CallState()
        data class Ending(val reason: String) : CallState()
        data class Ended(val reason: String, val duration: Long = 0) : CallState()
        data class Failed(val error: String) : CallState()
    }

    data class MissedCallNotice(
        val callId: String,
        val callerName: String,
        val mediaType: String,
        val timestamp: Long
    )

    private val _callState: MutableLiveData<CallState> = MutableLiveData(CallState.Idle)
    val callState: LiveData<CallState> = _callState

    private var currentCallId: String? = null
    private var remoteUserId: String? = null
    private var remoteUserName: String? = null
    private var remoteNickname: String? = null
    private var remoteAvatarUrl: String? = null
    private var remoteUserContactId: Int? = null
    private var isIncomingCall = false
    private var callStartTime: Long = 0
    private var pendingOfferSdp: String? = null
    private var isCleaningUp = false
    private var pendingCallMediaType: String = "audio"
    private data class PendingLocalIceCandidate(
        val sdp: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int
    )
    private val pendingLocalIceCandidates = mutableListOf<PendingLocalIceCandidate>()
    @Volatile private var signalingDescriptionSent = false
    @Volatile private var isDisposed = false

    private val _isMicrophoneAvailable = MutableLiveData(false)
    val isMicrophoneAvailable: LiveData<Boolean> = _isMicrophoneAvailable

    private val _microphoneModeMessage = MutableLiveData<String>()
    val microphoneModeMessage: LiveData<String> = _microphoneModeMessage


    private var ringingTimeoutJob: Job? = null
    private var incomingRingingTimeoutJob: Job? = null
    private var connectingTimeoutJob: Job? = null
    private var disconnectTimeoutJob: Job? = null

    private var callTimerJob: Job? = null
    private val _callDuration = MutableLiveData(0L)
    val callDuration: LiveData<Long> = _callDuration

    private val _isMuted = MutableLiveData(false)
    val isMuted: LiveData<Boolean> = _isMuted

    private val _isSpeakerOn = MutableLiveData(false)
    val isSpeakerOn: LiveData<Boolean> = _isSpeakerOn

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _isCameraEnabled = MutableLiveData(false)
    val isCameraEnabled: LiveData<Boolean> = _isCameraEnabled

    private val _isFrontCamera = MutableLiveData(true)
    val isFrontCamera: LiveData<Boolean> = _isFrontCamera

    private val _isLocalVideoActive = MutableLiveData(false)
    val isLocalVideoActive: LiveData<Boolean> = _isLocalVideoActive

    private val _isRemoteVideoActive = MutableLiveData(false)
    val isRemoteVideoActive: LiveData<Boolean> = _isRemoteVideoActive

    private val _videoUpgradeRequested = MutableLiveData(false)
    val videoUpgradeRequested: LiveData<Boolean> = _videoUpgradeRequested

    private val _videoUpgradeWaiting = MutableLiveData(false)
    val videoUpgradeWaiting: LiveData<Boolean> = _videoUpgradeWaiting

    private val _missedCall = MutableLiveData<MissedCallNotice?>()
    val missedCall: LiveData<MissedCallNotice?> = _missedCall

    fun initialize(callingRepository: CallRepository) {
        if (callingRepository.currentUserId.isEmpty()) {
            Log.e(TAG, "Cannot initialize CallViewModel - no user ID")
            _errorMessage.value = "User not logged in"
            return
        }

        Log.d(TAG, "Initializing CallViewModel for user: ${callingRepository.currentUserId}")

        try {
            this.callingRepository = callingRepository.apply {
                setLocalIceCandidateCallback { sdp, sdpMid, sdpMLineIndex ->
                    handleLocalIceCandidate(sdp, sdpMid, sdpMLineIndex)
                }
                setConnectionStateCallback { state -> handleConnectionStateChange(state) }
                setMicrophoneAvailabilityCallback { isAvailable, message ->
                    _isMicrophoneAvailable.postValue(isAvailable)
                    _microphoneModeMessage.postValue(message)
                }
                onLocalVideoActive = { active -> _isLocalVideoActive.postValue(active) }
                onRemoteVideoActive = { active ->
                    if (!active || pendingCallMediaType == "video") {
                        _isRemoteVideoActive.postValue(active)
                    }
                }

                onIncomingCall = { callId, callerId, callerUserId, callerName, callerUsername, offer, contactId, nickname, avatarUrl, mediaType ->
                    handleIncomingCall(callId, callerId, callerUserId, callerName, callerUsername, offer, contactId, nickname, avatarUrl, mediaType)
                }
                onCallAnswered = { callId, answer ->
                    if (isForCurrentCall(callId)) handleCallAnswered(answer)
                }
                onNewIceCandidate = { callId, candidate, sdpMid, sdpMLineIndex ->
                    if (isForCurrentCall(callId)) {
                        addOrQueueRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                    }
                }
                onCallRejected = { callId, reason ->
                    if (isForCurrentCall(callId)) viewModelScope.launch { handleCallRejected(reason) }
                }
                onCallEnded = { callId, reason, duration ->
                    if (isForCurrentCall(callId)) viewModelScope.launch { handleCallEnded(reason, duration) }
                }
                onRemoteCameraStateChanged = { callId, enabled ->
                    if (isForCurrentCall(callId)) {
                        Log.d(TAG, "Remote camera state changed: enabled=$enabled")
                        if (enabled) pendingCallMediaType = "video"
                        _isRemoteVideoActive.postValue(enabled)
                    }
                }
                onVideoUpgradeRequested = { callId ->
                    if (isForCurrentCall(callId)) {
                        if (_callState.value is CallState.Connected && _isCameraEnabled.value != true) {
                            _videoUpgradeRequested.postValue(true)
                        } else if (_isCameraEnabled.value == true) {
                            // Both peers may press the camera button at nearly the
                            // same time. Treat an already-video call as accepted.
                            viewModelScope.launch {
                                remoteUserId?.let {
                                    respondToVideoUpgrade(currentCallId.orEmpty(), it, true)
                                }
                            }
                        }
                    }
                }
                onVideoUpgradeResponded = { callId, accepted ->
                    if (isForCurrentCall(callId)) {
                        viewModelScope.launch { handleVideoUpgradeResponse(accepted) }
                    }
                }
                onMissedCall = { callId, callerName, mediaType, timestamp ->
                    _missedCall.postValue(MissedCallNotice(callId, callerName, mediaType, timestamp))
                }
                onCallAnsweredElsewhere = { callId ->
                    if (isForCurrentCall(callId)) {
                        Log.i(TAG, "Call answered on another device — dismissing incoming screen")
                        callingRepository?.clearPendingIncomingCall()
                        _callState.postValue(CallState.Ended("answered_elsewhere", 0))
                        viewModelScope.launch { cleanup() }
                    }
                }
                onSignalingError = { message, _ ->
                    Log.e(TAG, "Signaling error: $message")
                    _errorMessage.postValue(message)
                }

                initializeWebRTC()
            }

            // Register the event callbacks before subscribing, so an offer
            // arriving immediately after the channel connects cannot be lost.
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                callingRepository.observeSignalingEvents()
            }
            viewModelScope.launch { callingRepository.initializeSignaling() }

            Log.i(TAG, "CallViewModel initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CallViewModel", e)
            _errorMessage.value = "Failed to initialize calling: ${e.message}"
        }
    }


    fun initVideoRenderers(localRenderer: Any?, remoteRenderer: Any?) {
        callingRepository?.initVideoRenderer(localRenderer, mirror = true)
        callingRepository?.initVideoRenderer(remoteRenderer, mirror = false)
    }

    fun attachLocalVideo(renderer: Any?) = callingRepository?.attachLocalVideo(renderer)
    fun attachRemoteVideo(renderer: Any?) = callingRepository?.attachRemoteVideo(renderer)
    fun detachLocalVideo(renderer: Any?) = callingRepository?.detachLocalVideo(renderer)
    fun detachRemoteVideo(renderer: Any?) = callingRepository?.detachRemoteVideo(renderer)

    fun startCallAudio(preferSpeaker: Boolean = false) {
        callingRepository?.startCallAudio(preferSpeaker)
        _isSpeakerOn.value = preferSpeaker
    }
    fun stopCallAudio() = callingRepository?.stopCallAudio()


    fun initiateCall(targetUserId: String, contactName: String, contactId: Int? = null, nickname: String? = null, avatarUrl: String? = null, withVideo: Boolean = false) {
        val currentState = _callState.value
        if (currentState !is CallState.Idle && currentState !is CallState.Ended && currentState !is CallState.Failed) {
            Log.w(TAG, "Cannot initiate call - already in call state: $currentState")
            _errorMessage.value = "Already in a call"
            return
        }

        if (currentState is CallState.Ended || currentState is CallState.Failed) {
            _callState.value = CallState.Idle
        }

        Log.i(TAG, "Initiating call to: $contactName (User ID: $targetUserId)")

        _errorMessage.value = null
        _callState.value = CallState.Initiating(targetUserId, contactName, nickname, avatarUrl)
        remoteUserId = targetUserId
        remoteUserName = contactName
        remoteUserContactId = contactId
        remoteNickname = nickname
        remoteAvatarUrl = avatarUrl
        isIncomingCall = false
        currentCallId = generateCallId()
        synchronized(pendingLocalIceCandidates) {
            pendingLocalIceCandidates.clear()
            signalingDescriptionSent = false
        }

        viewModelScope.launch {
            try {
                val repo = callingRepository ?: throw Exception("Repository not initialized")
                // Relay credentials expire. Refresh for every call so a tablet
                // that has stayed open for days can still cross different NATs.
                repo.refreshTurnConfiguration()
                repo.recheckMicrophoneAvailability()
                repo.updateWebRTCStatus("in_call")
                val videoEnabled = withVideo && repo.hasCamera()
                val mediaType = if (videoEnabled) "video" else "audio"
                repo.setVideoEnabled(videoEnabled)
                _isCameraEnabled.postValue(videoEnabled)
                pendingCallMediaType = mediaType
                val offer = repo.createOffer()
                repo.sendCallOffer(currentCallId.orEmpty(), targetUserId, offer, mediaType)
                flushLocalIceCandidates()
                _callState.value = CallState.RingingOutgoing(targetUserId, contactName, nickname, avatarUrl, mediaType)
                startRingingTimeout()
                Log.i(TAG, "Call offer sent to $contactName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate call", e)
                _callState.value = CallState.Failed("Failed to initiate call: ${e.message}")
                cleanup()
            }
        }
    }

    fun answerCall(answerMediaType: String? = null) {
        val currentState = _callState.value
        if (currentState !is CallState.RingingIncoming) {
            Log.w(TAG, "Cannot answer - not in ringing incoming state")
            return
        }

        val requestedMediaType = if (
            (answerMediaType ?: pendingCallMediaType).equals("video", ignoreCase = true)
        ) "video" else "audio"
        Log.i(TAG, "Answering call from: ${currentState.callerName} as $requestedMediaType")
        cancelIncomingRingingTimeout()
        callingRepository?.clearPendingIncomingCall()
        beginConnecting()
        synchronized(pendingLocalIceCandidates) {
            pendingLocalIceCandidates.clear()
            signalingDescriptionSent = false
        }

        viewModelScope.launch {
            try {
                val repo = callingRepository ?: throw Exception("Repository not initialized")
                // Incoming calls can arrive long after startup as well.
                repo.refreshTurnConfiguration()
                repo.recheckMicrophoneAvailability()
                repo.updateWebRTCStatus("in_call")
                val offerSdp = pendingOfferSdp ?: throw Exception("No offer SDP available")
                val videoEnabled = requestedMediaType == "video" && repo.hasCamera()
                pendingCallMediaType = if (videoEnabled) "video" else "audio"
                repo.setVideoEnabled(videoEnabled)
                _isCameraEnabled.postValue(videoEnabled)
                val answer = repo.createAnswer(offerSdp) 
                repo.sendCallAnswer(currentCallId.orEmpty(), remoteUserId ?: currentState.callerId, answer)
                flushLocalIceCandidates()
                repo.notifyAnsweredElsewhere(currentCallId.orEmpty())
                Log.i(TAG, "Call answer sent; waiting for the media connection")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to answer call", e)
                remoteUserId?.let {
                    runCatching { callingRepository?.endCall(currentCallId.orEmpty(), it, "answer_failed", 0) }
                }
                _callState.value = CallState.Failed("Failed to answer call: ${e.message}")
                cleanup()
            }
        }
    }

    fun rejectCall() {
        val currentState = _callState.value
        if (currentState !is CallState.RingingIncoming) {
            Log.w(TAG, "Cannot reject - not in ringing incoming state")
            return
        }
        Log.i(TAG, "Rejecting call from: ${currentState.callerName}")
        cancelIncomingRingingTimeout()
        callingRepository?.clearPendingIncomingCall()
        viewModelScope.launch {
            try {
                callingRepository?.rejectCall(currentCallId.orEmpty(), remoteUserId ?: currentState.callerId, "user_declined")
                _callState.value = CallState.Ended("rejected", 0)
                logCallToHistory("missed", 0, "user_declined")
                cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject call", e)
                _callState.value = CallState.Ended("rejected", 0)
                cleanup()
            }
        }
    }

    fun endCall() {
        val currentState = _callState.value
        val canEndCall = currentState is CallState.Initiating ||
            currentState is CallState.RingingOutgoing ||
            currentState is CallState.RingingIncoming ||
            currentState is CallState.Connecting ||
            currentState is CallState.Connected
        if (!canEndCall) {
            Log.d(TAG, "Ignoring endCall in non-active state: $currentState")
            return
        }

        Log.i(TAG, "Ending call - current state: $currentState")
        _callState.value = CallState.Ending("user_hangup")
        viewModelScope.launch {
            try {
                val duration = if (callStartTime > 0) (System.currentTimeMillis() - callStartTime) / 1000 else 0L
                remoteUserId?.let { callingRepository?.endCall(currentCallId.orEmpty(), it, "user_hangup", duration) }
                _callState.value = CallState.Ended("user_hangup", duration)
                logCallToHistory(if (isIncomingCall) "incoming" else "outgoing", duration, "user_hangup")
                cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end call gracefully", e)
                _callState.value = CallState.Ended("user_hangup", 0)
                cleanup()
            }
        }
    }

    fun getRemoteUserId(): String? = remoteUserId
    fun getRemoteUserName(): String? = remoteUserName
    fun isIncomingCall(): Boolean = isIncomingCall
    fun hasCamera(): Boolean = callingRepository?.hasCamera() ?: false
    fun hasMultipleCameras(): Boolean = callingRepository?.hasMultipleCameras() ?: false
    fun isCurrentCallVideo(): Boolean = pendingCallMediaType == "video"
    fun clearMissedCallNotice() { _missedCall.value = null }
    fun reportMissedCall(callId: String, callerName: String, mediaType: String) {
        _missedCall.postValue(
            MissedCallNotice(callId, callerName, mediaType, System.currentTimeMillis())
        )
    }

    fun toggleMute() {
        val nowMuted = !(_isMuted.value ?: false)
        if (nowMuted) callingRepository?.mute() else callingRepository?.unmute()
        _isMuted.value = nowMuted
        Log.d(TAG, "Microphone ${if (nowMuted) "muted" else "unmuted"}")
    }

    fun toggleSpeaker() {
        val nowSpeaker = !(_isSpeakerOn.value ?: false)
        if (nowSpeaker) callingRepository?.enableSpeaker() else callingRepository?.disableSpeaker()
        _isSpeakerOn.value = nowSpeaker
        Log.d(TAG, "Speaker ${if (nowSpeaker) "enabled" else "disabled"}")
    }

    fun toggleCamera() {
        if (!hasCamera()) {
            Log.w(TAG, "Camera toggle ignored because no camera is available")
            return
        }
        val newState = !(_isCameraEnabled.value ?: false)
        if (newState) {
            if (pendingCallMediaType == "audio" && _callState.value is CallState.Connected) {
                if (_videoUpgradeWaiting.value == true) return
                _videoUpgradeWaiting.value = true
                viewModelScope.launch {
                    try {
                        remoteUserId?.let {
                            callingRepository?.requestVideoUpgrade(currentCallId.orEmpty(), it)
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not request video upgrade", error)
                        _videoUpgradeWaiting.postValue(false)
                    }
                }
                return
            }
            enableLocalVideo()
        } else {
            callingRepository?.setVideoEnabled(false)
            callingRepository?.stopVideoCapture()
            _isCameraEnabled.value = false
            viewModelScope.launch {
                remoteUserId?.let {
                    callingRepository?.sendCameraState(currentCallId.orEmpty(), it, false)
                }
            }
        }
        Log.d(TAG, "Camera ${if (newState) "started" else "stopped"}")
    }

    /** Called by the in-app ViKom dialog on the receiving device. */
    fun respondToVideoUpgrade(accepted: Boolean) {
        if (_videoUpgradeRequested.value != true) return
        _videoUpgradeRequested.value = false
        if (accepted && hasCamera()) enableLocalVideo()
        viewModelScope.launch {
            remoteUserId?.let {
                callingRepository?.respondToVideoUpgrade(
                    currentCallId.orEmpty(),
                    it,
                    accepted && hasCamera()
                )
            }
        }
    }

    private suspend fun respondToVideoUpgrade(
        callId: String,
        targetUserId: String,
        accepted: Boolean
    ) {
        callingRepository?.respondToVideoUpgrade(callId, targetUserId, accepted)
    }

    private suspend fun handleVideoUpgradeResponse(accepted: Boolean) {
        if (_videoUpgradeWaiting.value != true) return
        _videoUpgradeWaiting.postValue(false)
        if (accepted) enableLocalVideo()
    }

    private fun enableLocalVideo() {
        pendingCallMediaType = "video"
        callingRepository?.setVideoEnabled(true)
        callingRepository?.startVideoCapture()
        _isCameraEnabled.value = true
        viewModelScope.launch {
            remoteUserId?.let {
                callingRepository?.sendCameraState(currentCallId.orEmpty(), it, true)
            }
        }
    }

    fun switchCamera() {
        val repository = callingRepository ?: return
        if (_isCameraEnabled.value != true || !repository.hasMultipleCameras()) return
        repository.switchCamera { success, frontFacing ->
            if (success) {
                _isFrontCamera.postValue(frontFacing)
            }
        }
    }

    private fun addOrQueueRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        callingRepository?.addOrQueueRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    private fun handleIncomingCall(
        callId: String, callerId: String, callerUserId: String, callerName: String, callerUsername: String,
        offer: String, contactId: Int?, nickname: String?, avatarUrl: String?, mediaType: String = "audio"
    ) {
        Log.i(TAG, "Incoming call from: $callerName ($callerId), type=$mediaType")
        val currentState = _callState.value
        if (currentState !is CallState.Idle && currentState !is CallState.Ended && currentState !is CallState.Failed) {
            Log.w(TAG, "Rejecting incoming call - already in call state: $currentState")
            callingRepository?.clearPendingIncomingCall()
            viewModelScope.launch { callingRepository?.rejectCall(callId, callerUserId, "busy") }
            return
        }
        remoteUserId = callerUserId
        remoteUserName = callerName
        remoteUserContactId = contactId
        remoteNickname = nickname
        remoteAvatarUrl = avatarUrl
        isIncomingCall = true
        currentCallId = callId.ifBlank(::generateCallId)
        pendingOfferSdp = offer
        pendingCallMediaType = if (mediaType.equals("video", ignoreCase = true)) "video" else "audio"
        _callState.value = CallState.RingingIncoming(
            callerId,
            callerName,
            callerUsername,
            nickname,
            avatarUrl,
            pendingCallMediaType
        )
        startIncomingRingingTimeout()
    }

    private fun handleCallAnswered(answer: String) {
        Log.i(TAG, "Call answered by remote peer")
        val currentState = _callState.value
        if (currentState !is CallState.RingingOutgoing && currentState !is CallState.Initiating) {
            Log.w(TAG, "Ignoring call answer - not in outgoing ringing state (current: $currentState)")
            return
        }
        cancelRingingTimeout()
        beginConnecting()
        viewModelScope.launch {
            try {
                val result = callingRepository?.handleAnswer(answer) 
                if (result?.isSuccess == true) {
                    Log.i(TAG, "Remote answer applied; waiting for the media connection")
                } else {
                    throw Exception("Failed to set remote answer")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle call answered", e)
                remoteUserId?.let {
                    runCatching { callingRepository?.endCall(currentCallId.orEmpty(), it, "answer_failed", 0) }
                }
                _callState.value = CallState.Failed("Failed to connect: ${e.message}")
                cleanup()
            }
        }
    }

    private fun handleLocalIceCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        Log.d(TAG, "Generated local ICE candidate")
        viewModelScope.launch {
            val pending = PendingLocalIceCandidate(sdp, sdpMid, sdpMLineIndex)
            val shouldQueue = synchronized(pendingLocalIceCandidates) {
                if (!signalingDescriptionSent) pendingLocalIceCandidates += pending
                !signalingDescriptionSent
            }
            if (shouldQueue) {
                Log.d(TAG, "Queued local ICE until the call description is delivered")
                return@launch
            }
            sendLocalIceCandidate(pending)
        }
    }

    private suspend fun flushLocalIceCandidates() {
        val queued = synchronized(pendingLocalIceCandidates) {
            signalingDescriptionSent = true
            pendingLocalIceCandidates.toList().also { pendingLocalIceCandidates.clear() }
        }
        // A tablet commonly gathers several candidates at once. Sending them
        // concurrently prevents HTTPS signaling latency from delaying the
        // ringing screen or the answer by several seconds.
        coroutineScope {
            queued.map { candidate ->
                async { sendLocalIceCandidate(candidate) }
            }.awaitAll()
        }
    }

    private suspend fun sendLocalIceCandidate(candidate: PendingLocalIceCandidate) {
        remoteUserId?.let { targetUserId ->
            callingRepository?.sendIceCandidate(
                currentCallId.orEmpty(),
                targetUserId,
                candidate.sdp,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }
    }

    private fun handleConnectionStateChange(state: CallConnectionState) {
        Log.d(TAG, "Connection state changed: $state")
        when (state) {
            CallConnectionState.CONNECTED,
            CallConnectionState.COMPLETED -> {
                Log.i(TAG, "WebRTC connection established")
                cancelDisconnectTimeout()
                markConnected()
            }
            CallConnectionState.DISCONNECTED -> {
                Log.w(TAG, "WebRTC connection disconnected; allowing a short recovery window")
                startDisconnectTimeout()
            }
            CallConnectionState.FAILED -> {
                // Trickle ICE can briefly report FAILED before candidates from
                // a slow HTTPS/TURN path arrive. Keep the peer alive for the
                // same recovery window used after a disconnect.
                Log.w(TAG, "WebRTC connection failed; allowing late ICE candidates to recover")
                if (isCleaningUp) return
                startDisconnectTimeout()
            }
            CallConnectionState.CLOSED -> Log.i(TAG, "WebRTC connection closed")
            CallConnectionState.OTHER  -> {}
        }
    }

    private suspend fun handleCallRejected(reason: String) {
        val currentState = _callState.value
        if (currentState is CallState.Idle || currentState is CallState.Ended || currentState is CallState.Failed) {
            Log.d(TAG, "Ignoring call_rejected ($reason) — already in terminal state")
            return
        }
        Log.i(TAG, "Call rejected: $reason")
        _callState.value = CallState.Ended("rejected", 0)
        logCallToHistory("outgoing", 0, "rejected")
        cleanup()
    }

    private suspend fun handleCallEnded(reason: String, duration: Long) {
        val currentState = _callState.value
        if (currentState is CallState.Idle || currentState is CallState.Ended || currentState is CallState.Failed) {
            Log.d(TAG, "Ignoring call_ended ($reason) — already in terminal state")
            return
        }
        Log.i(TAG, "Call ended: $reason")
        cancelIncomingRingingTimeout()
        callingRepository?.clearPendingIncomingCall()
        _callState.value = CallState.Ended(reason, duration)
        logCallToHistory(if (isIncomingCall) "incoming" else "outgoing", duration, reason)
        cleanup()
    }

    private fun startRingingTimeout() {
        cancelRingingTimeout()
        ringingTimeoutJob = viewModelScope.launch {
            delay(RINGING_TIMEOUT_MS)
            if (_callState.value is CallState.RingingOutgoing) {
                Log.i(TAG, "Ringing timeout - no answer after ${RINGING_TIMEOUT_MS / 1000}s")
                _callState.value = CallState.Ended("no_answer", 0)
                logCallToHistory("outgoing", 0, "no_answer")
                remoteUserId?.let {
                    callingRepository?.endCall(currentCallId.orEmpty(), it, "no_answer", 0)
                }
                cleanup()
            }
        }
    }

    private fun cancelRingingTimeout() {
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
    }

    private fun startIncomingRingingTimeout() {
        cancelIncomingRingingTimeout()
        incomingRingingTimeoutJob = viewModelScope.launch {
            delay(RINGING_TIMEOUT_MS)
            if (_callState.value is CallState.RingingIncoming) {
                Log.w(TAG, "Incoming ringing timeout — marking the call as missed")
                remoteUserId?.let {
                    runCatching { callingRepository?.rejectCall(currentCallId.orEmpty(), it, "no_answer") }
                        .onFailure { error -> Log.w(TAG, "Could not notify caller of timeout", error) }
                }
                callingRepository?.clearPendingIncomingCall()
                _callState.value = CallState.Ended("no_answer", 0)
                logCallToHistory("missed", 0, "no_answer")
                cleanup()
            }
        }
    }

    private fun cancelIncomingRingingTimeout() {
        incomingRingingTimeoutJob?.cancel()
        incomingRingingTimeoutJob = null
    }

    private fun beginConnecting() {
        _callState.value = CallState.Connecting
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = viewModelScope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            if (_callState.value is CallState.Connecting) {
                Log.w(TAG, "Media connection timed out")
                terminateConnection("connection_timeout", "Connection timed out")
            }
        }
    }

    private fun markConnected() {
        viewModelScope.launch {
            if (_callState.value !is CallState.Connecting) return@launch
            connectingTimeoutJob?.cancel()
            connectingTimeoutJob = null
            _callState.value = CallState.Connected(
                remoteUserId = remoteUserId.orEmpty(),
                remoteUserName = remoteUserName ?: "Unknown",
                isIncoming = isIncomingCall,
                nickname = remoteNickname,
                avatarUrl = remoteAvatarUrl
            )
            startCallTimer()
            callingRepository?.startCallNotification(
                remoteUserName ?: "Unknown",
                isIncoming = isIncomingCall,
                mediaType = pendingCallMediaType
            )
        }
    }

    private fun startDisconnectTimeout() {
        val currentState = _callState.value
        if (currentState !is CallState.Connecting && currentState !is CallState.Connected) return
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = viewModelScope.launch {
            delay(DISCONNECT_GRACE_MS)
            val latestState = _callState.value
            if (latestState is CallState.Connecting || latestState is CallState.Connected) {
                terminateConnection("connection_lost", "Connection lost")
            }
        }
    }

    private fun cancelDisconnectTimeout() {
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = null
    }

    private fun terminateConnection(reason: String, message: String) {
        viewModelScope.launch {
            val currentState = _callState.value
            if (isCleaningUp || currentState is CallState.Idle ||
                currentState is CallState.Ended || currentState is CallState.Failed
            ) return@launch

            val duration = if (callStartTime > 0) {
                (System.currentTimeMillis() - callStartTime) / 1000
            } else 0L
            remoteUserId?.let {
                runCatching { callingRepository?.endCall(currentCallId.orEmpty(), it, reason, duration) }
                    .onFailure { error -> Log.w(TAG, "Could not notify peer of $reason", error) }
            }
            if (callStartTime > 0) {
                logCallToHistory(if (isIncomingCall) "incoming" else "outgoing", duration, reason)
            }
            _callState.value = CallState.Failed(message)
            cleanup()
        }
    }

    private fun startCallTimer() {
        callStartTime = System.currentTimeMillis()
        _callDuration.value = 0L
        callTimerJob?.cancel()
        callTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                val duration = (System.currentTimeMillis() - callStartTime) / 1000
                _callDuration.postValue(duration)
                callingRepository?.updateCallNotificationDuration(duration, _isMuted.value ?: false, _isSpeakerOn.value ?: false)
            }
        }
    }

    private fun stopCallTimer() {
        callTimerJob?.cancel()
        callTimerJob = null
        callingRepository?.stopCallNotification()
    }

    private fun isForCurrentCall(callId: String): Boolean =
        callId.isBlank() || currentCallId == callId

    private fun generateCallId(): String =
        "call_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}"

    private suspend fun logCallToHistory(callType: String, duration: Long, endReason: String) {
        callingRepository?.logCallToHistory(callType, duration, endReason, remoteUserName, remoteUserContactId)
    }

    private suspend fun cleanup() {
        if (isCleaningUp) return
        isCleaningUp = true
        Log.d(TAG, "Cleaning up call resources")

        cancelRingingTimeout()
        cancelIncomingRingingTimeout()
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
        cancelDisconnectTimeout()
        stopCallTimer()

        runCatching { callingRepository?.closeWebRTC() }
            .onFailure { error -> Log.e(TAG, "Failed to close WebRTC cleanly", error) }
        runCatching { callingRepository?.updateWebRTCStatus("available") }
            .onFailure { error -> Log.w(TAG, "Failed to restore available presence", error) }

        remoteUserId = null
        remoteUserName = null
        remoteUserContactId = null
        remoteNickname = null
        remoteAvatarUrl = null
        currentCallId = null
        isIncomingCall = false
        callStartTime = 0
        pendingOfferSdp = null
        pendingCallMediaType = "audio"
        synchronized(pendingLocalIceCandidates) {
            pendingLocalIceCandidates.clear()
            signalingDescriptionSent = false
        }
        _isMuted.value = false
        _isSpeakerOn.value = false
        _isCameraEnabled.value = false
        _isFrontCamera.value = true
        _isLocalVideoActive.value = false
        _isRemoteVideoActive.value = false
        _videoUpgradeRequested.value = false
        _videoUpgradeWaiting.value = false
        _callDuration.value = 0L
        _errorMessage.value = null

        isCleaningUp = false

        viewModelScope.launch {
            delay(500)
            if (_callState.value is CallState.Ended || _callState.value is CallState.Failed) {
                _callState.value = CallState.Idle
            }
        }
    }

    /**
     * Explicit lifecycle hook for the application-owned instance. This
     * ViewModel is not stored in a ViewModelStore, so Android will not call
     * onCleared() when a user logs out or the signaling stack is rebuilt.
     */
    suspend fun dispose() {
        if (isDisposed) return
        isDisposed = true
        Log.d(TAG, "Disposing application-owned CallViewModel")

        viewModelScope.coroutineContext.cancelChildren()
        cancelRingingTimeout()
        cancelIncomingRingingTimeout()
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
        cancelDisconnectTimeout()
        callTimerJob?.cancel()
        callTimerJob = null

        val repository = callingRepository
        repository?.stopCallNotification()
        repository?.stopCallAudio()
        runCatching { repository?.cleanupSignaling() }
            .onFailure { error -> Log.w(TAG, "Signaling cleanup failed during dispose", error) }
        runCatching { repository?.cleanupWebRTC() }
            .onFailure { error -> Log.w(TAG, "WebRTC cleanup failed during dispose", error) }
        callingRepository = null
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "CallViewModel cleared")
        if (!isDisposed) {
            stopCallTimer()
            callingRepository?.cleanupWebRTC()
        }
    }
}
