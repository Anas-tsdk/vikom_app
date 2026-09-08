package com.example.tv_caller_app.data.datasource

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.calling.audio.AudioRouteManager
import com.example.tv_caller_app.calling.service.CallNotificationDataSource
import com.example.tv_caller_app.calling.signaling.SignalingEvent
import com.example.tv_caller_app.calling.signaling.SignalingService
import com.example.tv_caller_app.calling.webrtc.MicrophoneMode
import com.example.tv_caller_app.calling.webrtc.WebRTCManager
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class CallDataSource(
    private val sessionManager: SessionManager,
    private val signalingService: SignalingService,
    private val webRTCManager: WebRTCManager,
    private val callNotificationDataSource: CallNotificationDataSource,
    private val audioRouteManager: AudioRouteManager
) {
    enum class CallConnectionState { CONNECTED, COMPLETED, DISCONNECTED, FAILED, CLOSED, OTHER }
    companion object {
        private const val TAG = "CallDataSource"
    }


    fun getUserId(): String? = sessionManager.getUserId()
    fun getUsername(): String? = sessionManager.getUsername()



    fun setLocalIceCandidateCallback(callback: ((sdp: String, sdpMid: String?, sdpMLineIndex: Int) -> Unit)?) {
        webRTCManager.onIceCandidate = if (callback == null) null else { candidate: IceCandidate ->
            callback(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
        }
    }

    fun setConnectionStateCallback(callback: ((CallConnectionState) -> Unit)?) {
        webRTCManager.onConnectionStateChange = if (callback == null) null else { state: PeerConnection.IceConnectionState ->
            callback(when (state) {
                PeerConnection.IceConnectionState.CONNECTED     -> CallConnectionState.CONNECTED
                PeerConnection.IceConnectionState.COMPLETED    -> CallConnectionState.COMPLETED
                PeerConnection.IceConnectionState.DISCONNECTED -> CallConnectionState.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED       -> CallConnectionState.FAILED
                PeerConnection.IceConnectionState.CLOSED       -> CallConnectionState.CLOSED
                else                                            -> CallConnectionState.OTHER
            })
        }
    }

    fun setMicrophoneAvailabilityCallback(callback: ((isAvailable: Boolean, message: String) -> Unit)?) {
        webRTCManager.onMicrophoneModeChanged = if (callback == null) null else { mode: MicrophoneMode, message: String ->
            callback(mode == MicrophoneMode.TWO_WAY, message)
        }
    }



    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    fun initializeWebRTC(
        onLocalVideoActive: ((Boolean) -> Unit)? = null,
        onRemoteVideoActive: ((Boolean) -> Unit)? = null
    ) {
        webRTCManager.onMicrophoneModeChanged = null 
        webRTCManager.onLocalVideoTrack = { track: VideoTrack ->
            localVideoTrack = track
            onLocalVideoActive?.invoke(true)
        }
        webRTCManager.onRemoteVideoTrack = { track: VideoTrack ->
            remoteVideoTrack = track
            onRemoteVideoActive?.invoke(true)
        }
        webRTCManager.initialize()
    }

    fun initVideoRenderer(renderer: Any?, mirror: Boolean) {
        val r = renderer as? SurfaceViewRenderer ?: return
        try {
            r.init(webRTCManager.eglBase.eglBaseContext, null)
            r.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            r.setMirror(mirror)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init video renderer", e)
        }
    }

    fun attachLocalVideo(renderer: Any?) {
        val r = renderer as? SurfaceViewRenderer ?: return
        localVideoTrack?.addSink(r)
    }

    fun attachRemoteVideo(renderer: Any?) {
        val r = renderer as? SurfaceViewRenderer ?: return
        remoteVideoTrack?.addSink(r)
    }

    fun detachLocalVideo(renderer: Any?) {
        val r = renderer as? SurfaceViewRenderer ?: return
        localVideoTrack?.removeSink(r)
    }

    fun detachRemoteVideo(renderer: Any?) {
        val r = renderer as? SurfaceViewRenderer ?: return
        remoteVideoTrack?.removeSink(r)
    }

    fun clearVideoTracks() {
        localVideoTrack = null
        remoteVideoTrack = null
    }



    fun hasCamera(): Boolean = webRTCManager.hasCamera()
    fun setVideoEnabled(enabled: Boolean) = webRTCManager.setVideoEnabled(enabled)
    fun startVideoCapture() = webRTCManager.startVideoCapture()
    fun stopVideoCapture() = webRTCManager.stopVideoCapture()
    fun mute() = webRTCManager.mute()
    fun unmute() = webRTCManager.unmute()
    fun enableSpeaker() = webRTCManager.enableSpeaker()
    fun disableSpeaker() = webRTCManager.disableSpeaker()
    suspend fun createOffer(): String = webRTCManager.createOffer()
    suspend fun createAnswer(offerSdp: String): String = webRTCManager.createAnswer(offerSdp)
    suspend fun handleAnswer(answerSdp: String): Result<Unit> = webRTCManager.handleAnswer(answerSdp)
    fun addIceCandidate(candidateSdp: String, sdpMid: String?, sdpMLineIndex: Int?) =
        webRTCManager.addIceCandidate(candidateSdp, sdpMid, sdpMLineIndex)
    suspend fun closeWebRTC() = webRTCManager.close()
    fun cleanupWebRTC() = webRTCManager.cleanup()



    var onSignalingIncomingCall: (suspend (callerId: String, callerUserId: String, callerName: String, callerUsername: String, offer: String, mediaType: String) -> Unit)? = null
    var onSignalingCallAnswered: ((answer: String) -> Unit)? = null
    var onSignalingNewIceCandidate: ((candidate: String, sdpMid: String?, sdpMLineIndex: Int?) -> Unit)? = null
    var onSignalingCallRejected: ((reason: String) -> Unit)? = null
    var onSignalingCallEnded: ((reason: String, duration: Long) -> Unit)? = null
    var onSignalingRemoteCameraStateChanged: ((enabled: Boolean) -> Unit)? = null
    var onSignalingCallAnsweredElsewhere: (() -> Unit)? = null
    var onSignalingError: ((message: String, exception: Throwable?) -> Unit)? = null
    var onSignalingConnected: (() -> Unit)? = null
    var onSignalingDisconnected: (() -> Unit)? = null



    suspend fun initializeSignaling() = signalingService.initialize()

    suspend fun observeSignalingEvents() {
        signalingService.events.collect { event ->
            when (event) {
                is SignalingEvent.IncomingCall ->
                    onSignalingIncomingCall?.invoke(event.callerId, event.callerUserId, event.callerName, event.callerUsername, event.offer, event.mediaType)
                is SignalingEvent.CallAnswered ->
                    onSignalingCallAnswered?.invoke(event.answer)
                is SignalingEvent.NewIceCandidate ->
                    onSignalingNewIceCandidate?.invoke(event.candidate, event.sdpMid, event.sdpMLineIndex)
                is SignalingEvent.CallRejected ->
                    onSignalingCallRejected?.invoke(event.reason)
                is SignalingEvent.CallEnded ->
                    onSignalingCallEnded?.invoke(event.reason, event.duration)
                is SignalingEvent.RemoteCameraStateChanged ->
                    onSignalingRemoteCameraStateChanged?.invoke(event.enabled)
                is SignalingEvent.CallAnsweredElsewhere ->
                    onSignalingCallAnsweredElsewhere?.invoke()
                is SignalingEvent.Error ->
                    onSignalingError?.invoke(event.message, event.exception)
                is SignalingEvent.Connected -> {
                    Log.d(TAG, "Signaling connected")
                    onSignalingConnected?.invoke()
                }
                is SignalingEvent.Disconnected -> {
                    Log.d(TAG, "Signaling disconnected")
                    onSignalingDisconnected?.invoke()
                }
            }
        }
    }

    suspend fun sendCallOffer(targetUserId: String, callerName: String, callerUsername: String, offer: String, mediaType: String = "audio") =
        signalingService.sendCallOffer(targetUserId, callerName, callerUsername, offer, mediaType)

    suspend fun sendCallAnswer(callerId: String, calleeName: String, answer: String) =
        signalingService.sendCallAnswer(callerId, calleeName, answer)

    suspend fun notifyAnsweredElsewhere(ownUserId: String) =
        signalingService.notifyAnsweredElsewhere(ownUserId)

    suspend fun rejectCall(callerId: String, reason: String) =
        signalingService.rejectCall(callerId, reason)

    suspend fun endCall(otherUserId: String, reason: String, duration: Long) =
        signalingService.endCall(otherUserId, reason, duration)

    suspend fun sendIceCandidate(targetUserId: String, candidate: String, sdpMid: String?, sdpMLineIndex: Int?) =
        signalingService.sendIceCandidate(targetUserId, candidate, sdpMid, sdpMLineIndex)

    suspend fun sendCameraState(targetUserId: String, enabled: Boolean) =
        signalingService.sendCameraState(targetUserId, enabled)

    suspend fun cleanupSignaling() = signalingService.cleanup()



    fun startCallAudio() = audioRouteManager.startCallAudio()
    fun stopCallAudio() = audioRouteManager.stopCallAudio()



    fun startCallNotification(contactName: String, isIncoming: Boolean) =
        callNotificationDataSource.startService(contactName, isIncoming)

    fun stopCallNotification() = callNotificationDataSource.stopService()

    fun updateCallNotificationDuration(durationSeconds: Long, isMuted: Boolean, isSpeakerOn: Boolean) =
        callNotificationDataSource.updateCallDuration(durationSeconds, isMuted, isSpeakerOn)


}
