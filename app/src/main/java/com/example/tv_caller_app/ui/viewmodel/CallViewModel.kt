package com.example.tv_caller_app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.tv_caller_app.data.repository.CallConnectionState
import com.example.tv_caller_app.data.repository.CallRepository

class CallViewModel : ViewModel() {

    companion object {
        private const val TAG = "CallViewModel"
        const val RINGING_TIMEOUT_MS = 15_000L
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

    private val _isMicrophoneAvailable = MutableLiveData(false)
    val isMicrophoneAvailable: LiveData<Boolean> = _isMicrophoneAvailable

    private val _microphoneModeMessage = MutableLiveData<String>()
    val microphoneModeMessage: LiveData<String> = _microphoneModeMessage


    private var ringingTimeoutJob: Job? = null
    private var incomingRingingTimeoutJob: Job? = null

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

    private val _isLocalVideoActive = MutableLiveData(false)
    val isLocalVideoActive: LiveData<Boolean> = _isLocalVideoActive

    private val _isRemoteVideoActive = MutableLiveData(false)
    val isRemoteVideoActive: LiveData<Boolean> = _isRemoteVideoActive

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

                onIncomingCall = { callerId, callerUserId, callerName, callerUsername, offer, contactId, nickname, avatarUrl, mediaType ->
                    handleIncomingCall(callerId, callerUserId, callerName, callerUsername, offer, contactId, nickname, avatarUrl, mediaType)
                }
                onCallAnswered = { answer -> handleCallAnswered(answer) }
                onNewIceCandidate = { candidate, sdpMid, sdpMLineIndex ->
                    addOrQueueRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
                }
                onCallRejected = { reason ->
                    viewModelScope.launch { handleCallRejected(reason) }
                }
                onCallEnded = { reason, duration ->
                    viewModelScope.launch { handleCallEnded(reason, duration) }
                }
                onRemoteCameraStateChanged = { enabled ->
                    Log.d(TAG, "Remote camera state changed: enabled=$enabled")
                    _isRemoteVideoActive.postValue(enabled)
                }
                onCallAnsweredElsewhere = {
                    Log.i(TAG, "Call answered on another device — dismissing incoming screen")
                    callingRepository?.clearPendingIncomingCall()
                    _callState.postValue(CallState.Ended("answered_elsewhere", 0))
                }
                onSignalingError = { message, _ ->
                    Log.e(TAG, "Signaling error: $message")
                    _errorMessage.postValue(message)
                }

                initializeWebRTC()
            }

            viewModelScope.launch {
                callingRepository.initializeSignaling()
                callingRepository.observeSignalingEvents()
            }

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

    fun startCallAudio() = callingRepository?.startCallAudio()
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

        viewModelScope.launch {
            try {
                val repo = callingRepository ?: throw Exception("Repository not initialized")
                repo.updateWebRTCStatus("in_call")
                val mediaType = if (withVideo) "video" else "audio"
                val videoEnabled = withVideo && repo.hasCamera()
                repo.setVideoEnabled(videoEnabled)
                _isCameraEnabled.postValue(videoEnabled)
                pendingCallMediaType = mediaType
                val offer = repo.createOffer()
                repo.sendCallOffer(targetUserId, offer, mediaType)
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

        val resolvedMediaType = answerMediaType ?: pendingCallMediaType
        Log.i(TAG, "Answering call from: ${currentState.callerName} as $resolvedMediaType")
        cancelIncomingRingingTimeout()
        callingRepository?.clearPendingIncomingCall()
        _callState.value = CallState.Connecting

        viewModelScope.launch {
            try {
                val repo = callingRepository ?: throw Exception("Repository not initialized")
                repo.updateWebRTCStatus("in_call")
                val offerSdp = pendingOfferSdp ?: throw Exception("No offer SDP available")
                if (resolvedMediaType == "video" && repo.hasCamera()) {
                    repo.setVideoEnabled(true)
                    _isCameraEnabled.postValue(true)
                }
                val answer = repo.createAnswer(offerSdp) 
                repo.sendCallAnswer(remoteUserId ?: currentState.callerId, answer)
                repo.notifyAnsweredElsewhere()

                _callState.value = CallState.Connected(
                    remoteUserId = currentState.callerId,
                    remoteUserName = currentState.callerName,
                    isIncoming = true,
                    nickname = remoteNickname,
                    avatarUrl = remoteAvatarUrl
                )
                startCallTimer()
                repo.startCallNotification(currentState.callerName, isIncoming = true)
                Log.i(TAG, "Call answered successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to answer call", e)
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
                callingRepository?.rejectCall(remoteUserId ?: currentState.callerId, "user_declined")
                _callState.value = CallState.Ended("rejected", 0)
                logCallToHistory("missed", 0, "user_declined")
                cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject call", e)
                cleanup()
            }
        }
    }

    fun endCall() {
        Log.i(TAG, "Ending call - current state: ${_callState.value}")
        _callState.value = CallState.Ending("user_hangup")
        viewModelScope.launch {
            try {
                val duration = if (callStartTime > 0) (System.currentTimeMillis() - callStartTime) / 1000 else 0L
                remoteUserId?.let { callingRepository?.endCall(it, "user_hangup", duration) }
                _callState.value = CallState.Ended("user_hangup", duration)
                logCallToHistory(if (isIncomingCall) "incoming" else "outgoing", duration, "user_hangup")
                cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end call gracefully", e)
                cleanup()
            }
        }
    }

    fun getRemoteUserId(): String? = remoteUserId
    fun getRemoteUserName(): String? = remoteUserName
    fun isIncomingCall(): Boolean = isIncomingCall
    fun hasCamera(): Boolean = callingRepository?.hasCamera() ?: false

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
        val newState = !(_isCameraEnabled.value ?: false)
        if (newState) callingRepository?.startVideoCapture() else callingRepository?.stopVideoCapture()
        _isCameraEnabled.value = newState
        Log.d(TAG, "Camera ${if (newState) "started" else "stopped"}")
        viewModelScope.launch {
            remoteUserId?.let { callingRepository?.sendCameraState(it, newState) }
        }
    }

    private fun addOrQueueRemoteIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        callingRepository?.addOrQueueRemoteIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    private fun handleIncomingCall(
        callerId: String, callerUserId: String, callerName: String, callerUsername: String,
        offer: String, contactId: Int?, nickname: String?, avatarUrl: String?, mediaType: String = "audio"
    ) {
        Log.i(TAG, "Incoming call from: $callerName ($callerId), type=$mediaType")
        val currentState = _callState.value
        if (currentState !is CallState.Idle && currentState !is CallState.Ended && currentState !is CallState.Failed) {
            Log.w(TAG, "Rejecting incoming call - already in call state: $currentState")
            viewModelScope.launch { callingRepository?.rejectCall(callerUserId, "busy") }
            return
        }
        remoteUserId = callerUserId
        remoteUserName = callerName
        remoteUserContactId = contactId
        remoteNickname = nickname
        remoteAvatarUrl = avatarUrl
        isIncomingCall = true
        currentCallId = generateCallId()
        pendingOfferSdp = offer
        pendingCallMediaType = mediaType
        _callState.value = CallState.RingingIncoming(callerId, callerName, callerUsername, nickname, avatarUrl, mediaType)
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
        viewModelScope.launch {
            try {
                val result = callingRepository?.handleAnswer(answer) 
                if (result?.isSuccess == true) {
                    _callState.value = CallState.Connected(
                        remoteUserId = remoteUserId ?: "",
                        remoteUserName = remoteUserName ?: "Unknown",
                        isIncoming = false,
                        nickname = remoteNickname,
                        avatarUrl = remoteAvatarUrl
                    )
                    startCallTimer()
                    callingRepository?.startCallNotification(remoteUserName ?: "Unknown", isIncoming = false)
                } else {
                    throw Exception("Failed to set remote answer")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle call answered", e)
                _callState.value = CallState.Failed("Failed to connect: ${e.message}")
                cleanup()
            }
        }
    }

    private fun handleLocalIceCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        Log.d(TAG, "Generated local ICE candidate")
        viewModelScope.launch {
            remoteUserId?.let {
                callingRepository?.sendIceCandidate(it, sdp, sdpMid, sdpMLineIndex)
            }
        }
    }

    private fun handleConnectionStateChange(state: CallConnectionState) {
        Log.d(TAG, "Connection state changed: $state")
        when (state) {
            CallConnectionState.CONNECTED,
            CallConnectionState.COMPLETED  -> Log.i(TAG, "WebRTC connection established")
            CallConnectionState.DISCONNECTED -> Log.w(TAG, "WebRTC connection disconnected")
            CallConnectionState.FAILED -> {
                Log.e(TAG, "WebRTC connection failed")
                if (isCleaningUp) return
                _callState.postValue(CallState.Failed("Connection failed"))
                viewModelScope.launch { cleanup() }
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
                remoteUserId?.let { callingRepository?.endCall(it, "no_answer", 0) }
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
            delay(30_000L)
            if (_callState.value is CallState.RingingIncoming) {
                Log.w(TAG, "Incoming ringing timeout — resetting stuck state")
                callingRepository?.clearPendingIncomingCall()
                _callState.value = CallState.Idle
            }
        }
    }

    private fun cancelIncomingRingingTimeout() {
        incomingRingingTimeoutJob?.cancel()
        incomingRingingTimeoutJob = null
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

    private fun generateCallId(): String = "call_${System.currentTimeMillis()}"

    private suspend fun logCallToHistory(callType: String, duration: Long, endReason: String) {
        callingRepository?.logCallToHistory(callType, duration, endReason, remoteUserName, remoteUserContactId)
    }

    private suspend fun cleanup() {
        if (isCleaningUp) return
        isCleaningUp = true
        Log.d(TAG, "Cleaning up call resources")

        cancelRingingTimeout()
        cancelIncomingRingingTimeout()
        stopCallTimer()

        remoteUserId?.let {
            try { callingRepository?.endCall(it, "cleanup", 0) }
            catch (e: Exception) { Log.e(TAG, "Failed to notify remote of cleanup", e) }
        }

        callingRepository?.closeWebRTC()
        callingRepository?.updateWebRTCStatus("available")

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
        _isMuted.value = false
        _isSpeakerOn.value = false
        _isCameraEnabled.value = false
        _isLocalVideoActive.value = false
        _isRemoteVideoActive.value = false
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

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "CallViewModel cleared")
        stopCallTimer()
        callingRepository?.cleanupWebRTC()
        viewModelScope.launch { callingRepository?.cleanupSignaling() }
    }
}
