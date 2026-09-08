package com.example.tv_caller_app.calling.webrtc

import android.content.Context
import android.util.Log
import com.example.tv_caller_app.calling.audio.AudioRouteManager
import com.example.tv_caller_app.calling.audio.AudioTrackManager
import com.example.tv_caller_app.calling.audio.MicrophoneDetector
import com.example.tv_caller_app.calling.permissions.PermissionHelper
import com.example.tv_caller_app.calling.video.VideoCaptureManager
import com.example.tv_caller_app.calling.webrtc.MicrophoneMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WebRTCManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRTCManager"
        const val DEBUG_DISABLE_AUDIO = false
    }

    val eglBase: EglBase = EglBase.create()

    private val videoManager = VideoCaptureManager(context, eglBase)
    private val microphoneDetector = MicrophoneDetector(context)

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    private val audioTrackManager = AudioTrackManager()
    private val audioRouteManager = AudioRouteManager(context)

    private var isInitialized = false
    private var currentMicrophoneMode = MicrophoneMode.RECEIVE_ONLY

    private val statsScope = CoroutineScope(Dispatchers.IO)
    private var statsJob: Job? = null
    private var lastBytesSent = 0L
    private var lastBytesReceived = 0L

    sealed class ConnectionState {
        object Idle : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        object Disconnected : ConnectionState()
        object Failed : ConnectionState()
        object Closed : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionStateChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onLocalVideoTrack: ((VideoTrack) -> Unit)?
        get() = videoManager.onLocalVideoTrack
        set(value) { videoManager.onLocalVideoTrack = value }
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null
    var onRemoteStream: ((MediaStream) -> Unit)? = null
    var onMicrophoneModeChanged: ((MicrophoneMode, String) -> Unit)? = null

    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "WebRTC already initialized")
            return
        }

        if (DEBUG_DISABLE_AUDIO) {
            Log.w(TAG, "⚠️ DEBUG MODE: Audio disabled for emulator testing")
            Log.w(TAG, "⚠️ Signaling will work but no audio will be transmitted")
        }

        Log.i(TAG, "Initializing WebRTC...")

        try {
            if (DEBUG_DISABLE_AUDIO) {
                currentMicrophoneMode = MicrophoneMode.RECEIVE_ONLY
                Log.w(TAG, "Debug mode: Microphone disabled")
                onMicrophoneModeChanged?.invoke(currentMicrophoneMode, "Debug mode - audio disabled")
            } else {
                val hasPermission = PermissionHelper.hasAudioPermission(context)
                val hasHardware  = microphoneDetector.hasMicrophoneDevice()
                currentMicrophoneMode = if (hasPermission && hasHardware) {
                    MicrophoneMode.TWO_WAY
                } else {
                    if (!hasPermission) Log.w(TAG, "RECEIVE_ONLY: RECORD_AUDIO permission not granted")
                    if (!hasHardware)   Log.w(TAG, "RECEIVE_ONLY: no microphone hardware detected")
                    MicrophoneMode.RECEIVE_ONLY
                }
                onMicrophoneModeChanged?.invoke(currentMicrophoneMode, "")

                microphoneDetector.registerListener { hasMic, _ ->
                    val perm = PermissionHelper.hasAudioPermission(context)
                    val newMode = if (perm && hasMic) MicrophoneMode.TWO_WAY else MicrophoneMode.RECEIVE_ONLY
                    if (newMode != currentMicrophoneMode) {
                        val reason = if (!perm) "RECORD_AUDIO permission missing"
                                     else if (!hasMic) "microphone disconnected"
                                     else "microphone connected"
                        currentMicrophoneMode = newMode
                        onMicrophoneModeChanged?.invoke(currentMicrophoneMode, reason)
                    }
                }
            }

            if (DEBUG_DISABLE_AUDIO) {
                val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()

                PeerConnectionFactory.initialize(initOptions)

                val options = PeerConnectionFactory.Options()
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setAudioDeviceModule(null)
                    .createPeerConnectionFactory()
            } else {
                val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                    .createInitializationOptions()

                PeerConnectionFactory.initialize(initOptions)

                val adm = JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .createAudioDeviceModule()
                audioDeviceModule = adm

                val options = PeerConnectionFactory.Options()
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setAudioDeviceModule(adm)
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
                    .createPeerConnectionFactory()

                adm.release() 
                audioDeviceModule = null
            }

            isInitialized = true
            Log.i(TAG, "WebRTC initialized successfully in $currentMicrophoneMode mode")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC", e)
            throw e
        }
    }

    fun recheckMicrophoneAvailability(): MicrophoneMode {
        val hasPermission = PermissionHelper.hasAudioPermission(context)
        val hasHardware  = microphoneDetector.hasMicrophoneDevice()
        val newMode = if (hasPermission && hasHardware) {
            MicrophoneMode.TWO_WAY
        } else {
            if (!hasPermission) Log.w(TAG, "Recheck: RECORD_AUDIO permission not granted")
            if (!hasHardware)   Log.w(TAG, "Recheck: no microphone hardware detected")
            MicrophoneMode.RECEIVE_ONLY
        }

        if (newMode != currentMicrophoneMode) {
            Log.i(TAG, "Microphone mode changed: $currentMicrophoneMode -> $newMode")
            currentMicrophoneMode = newMode
            onMicrophoneModeChanged?.invoke(currentMicrophoneMode, "")
        }

        return currentMicrophoneMode
    }

    fun setVideoEnabled(enabled: Boolean) = videoManager.setVideoEnabled(enabled)

    fun hasCamera(): Boolean = videoManager.hasCamera()

    fun startVideoCapture() = videoManager.startCapture()

    fun stopVideoCapture() = videoManager.stopCapture()

    private fun startStatsLogging() {
        lastBytesSent = 0L
        lastBytesReceived = 0L
        statsJob = statsScope.launch {
            while (true) {
                delay(3000)
                val pc = peerConnection ?: break
                pc.getStats { report ->
                    var bytesSent = 0L
                    var bytesReceived = 0L
                    var rttMs = -1.0
                    var audioPacketsLost = 0L

                    for (stat in report.statsMap.values) {
                        when (stat.type) {
                            "outbound-rtp" -> {
                                val v = stat.members["bytesSent"]
                                bytesSent += when (v) {
                                    is Long -> v
                                    is Double -> v.toLong()
                                    is Int -> v.toLong()
                                    else -> 0L
                                }
                            }
                            "inbound-rtp" -> {
                                val v = stat.members["bytesReceived"]
                                bytesReceived += when (v) {
                                    is Long -> v
                                    is Double -> v.toLong()
                                    is Int -> v.toLong()
                                    else -> 0L
                                }
                                val lost = stat.members["packetsLost"]
                                audioPacketsLost += when (lost) {
                                    is Int -> lost.toLong()
                                    is Long -> lost
                                    is Double -> lost.toLong()
                                    else -> 0L
                                }
                            }
                            "remote-inbound-rtp" -> {
                                (stat.members["roundTripTime"] as? Double)?.let { rttMs = it * 1000 }
                            }
                        }
                    }

                    val sentDelta = bytesSent - lastBytesSent
                    val recvDelta = bytesReceived - lastBytesReceived
                    lastBytesSent = bytesSent
                    lastBytesReceived = bytesReceived

                    val sentKbps = (sentDelta * 8) / 3000  
                    val recvKbps = (recvDelta * 8) / 3000

                    Log.i("WebRTC_BANDWIDTH",
                        "↑ ${sentKbps} kbps | ↓ ${recvKbps} kbps | " +
                        "RTT: ${if (rttMs >= 0) "%.0f ms".format(rttMs) else "n/a"} | " +
                        "Packets lost: $audioPacketsLost"
                    )
                }
            }
        }
    }

    private fun stopStatsLogging() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun applyBitrateCap(pc: PeerConnection) {
        pc.senders.forEach { sender ->
            val params = sender.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings.forEach { it.maxBitrateBps = WebRTCConfig.VideoConstraints.MAX_BITRATE_BPS }
                sender.parameters = params
            }
        }
        Log.d(TAG, "Bitrate cap applied: ${WebRTCConfig.VideoConstraints.MAX_BITRATE_BPS / 1000} Kbps per sender")
    }

    private fun createAudioTrack(): AudioTrack? {
        val factory = peerConnectionFactory ?: return null
        return audioTrackManager.createAudioTrack(
            factory = factory,
            micMode = currentMicrophoneMode,
            debugDisabled = DEBUG_DISABLE_AUDIO,
            onModeChanged = { mode, msg ->
                currentMicrophoneMode = mode
                onMicrophoneModeChanged?.invoke(mode, msg)
            }
        )
    }

    private fun createPeerConnection(): PeerConnection? {
        try {
            val rtcConfig = WebRTCConfig.createRTCConfiguration()

            val observer = PeerConnectionObserver(
                onIceCandidateCallback = { candidate ->
                    Log.d(TAG, "ICE candidate generated: ${candidate.sdp}")
                    onIceCandidate?.invoke(candidate)
                },
                onConnectionChangeCallback = { state ->
                    Log.i(TAG, "ICE connection state: $state")

                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            _connectionState.value = ConnectionState.Connected
                            startStatsLogging()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _connectionState.value = ConnectionState.Disconnected
                            stopStatsLogging()
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            _connectionState.value = ConnectionState.Failed
                            stopStatsLogging()
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            _connectionState.value = ConnectionState.Closed
                            stopStatsLogging()
                        }
                        else -> {
                            _connectionState.value = ConnectionState.Connecting
                        }
                    }

                    onConnectionStateChange?.invoke(state)
                },
                onAddStreamCallback = { stream ->
                    Log.i(TAG, "Remote stream added")

                    audioTrackManager.onRemoteStream(stream)

                    if (stream.videoTracks.isNotEmpty()) {
                        remoteVideoTrack = stream.videoTracks[0]
                        remoteVideoTrack?.setEnabled(true)
                        Log.d(TAG, "Remote video track enabled")
                        onRemoteVideoTrack?.invoke(remoteVideoTrack!!)
                    }

                    onRemoteStream?.invoke(stream)
                },
                onRemoveStreamCallback = { stream ->
                    Log.i(TAG, "Remote stream removed")
                    audioTrackManager.onRemoveStream()
                }
            )

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
            Log.d(TAG, "Peer connection created")

            return peerConnection

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create peer connection", e)
            return null
        }
    }

    suspend fun createOffer(): String = suspendCoroutine { continuation ->
        try {
            if (!isInitialized) {
                throw IllegalStateException("WebRTC not initialized")
            }

            if (DEBUG_DISABLE_AUDIO) {
                Log.w(TAG, "Debug mode: Creating offer WITHOUT audio")
            }

            Log.i(TAG, "Creating offer in $currentMicrophoneMode mode...")
            _connectionState.value = ConnectionState.Connecting

            val pc = createPeerConnection()
                ?: throw RuntimeException("Failed to create peer connection")

            val audioTrack = createAudioTrack()
            val videoTrack = peerConnectionFactory?.let { videoManager.createVideoTrack(it) }

            val streamId = WebRTCConfig.StreamLabels.STREAM_ID
            if (audioTrack != null) {
                pc.addTrack(audioTrack, listOf(streamId))
                Log.d(TAG, "Local audio track added to peer connection")
            } else {
                Log.w(TAG, "No local audio track - RECEIVE_ONLY mode")
            }
            if (videoTrack != null) {
                pc.addTrack(videoTrack, listOf(streamId))
                Log.d(TAG, "Local video track added to peer connection")
            } else {
                Log.d(TAG, "No local video track - no camera on this device")
            }

            applyBitrateCap(pc)

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            }

            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        Log.d(TAG, "Offer created successfully")

                        val modifiedSdp = it

                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Local description set")
                                continuation.resume(modifiedSdp.description)
                            }

                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Set local description failed: $error")
                                continuation.resumeWith(Result.failure(RuntimeException(error)))
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, it)
                    } ?: run {
                        continuation.resumeWith(Result.failure(RuntimeException("SDP is null")))
                    }
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed: $error")
                    continuation.resumeWith(Result.failure(RuntimeException(error)))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(p0: String?) {}
            }, constraints)

        } catch (e: Exception) {
            Log.e(TAG, "Create offer exception", e)
            continuation.resumeWith(Result.failure(e))
        }
    }

    suspend fun createAnswer(offerSdp: String): String = suspendCoroutine { continuation ->
        try {
            if (!isInitialized) {
                throw IllegalStateException("WebRTC not initialized")
            }

            if (DEBUG_DISABLE_AUDIO) {
                Log.w(TAG, "Debug mode: Creating answer WITHOUT audio")
            }

            Log.i(TAG, "Creating answer in $currentMicrophoneMode mode...")
            _connectionState.value = ConnectionState.Connecting

            val pc = createPeerConnection()
                ?: throw RuntimeException("Failed to create peer connection")

            val audioTrack = createAudioTrack()
            val videoTrack = peerConnectionFactory?.let { videoManager.createVideoTrack(it) }

            val streamId = WebRTCConfig.StreamLabels.STREAM_ID
            if (audioTrack != null) {
                pc.addTrack(audioTrack, listOf(streamId))
                Log.d(TAG, "Local audio track added to peer connection")
            } else {
                Log.w(TAG, "No local audio track - RECEIVE_ONLY mode")
            }
            if (videoTrack != null) {
                pc.addTrack(videoTrack, listOf(streamId))
                Log.d(TAG, "Local video track added to peer connection")
            } else {
                Log.d(TAG, "No local video track - no camera on this device")
            }

            applyBitrateCap(pc)

            val remoteDescription = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description (offer) set successfully")

                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    }

                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {
                            sdp?.let {
                                Log.d(TAG, "Answer created successfully")

                                val modifiedSdp = it

                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        Log.d(TAG, "Local description (answer) set")
                                        continuation.resume(modifiedSdp.description)
                                    }

                                    override fun onSetFailure(error: String?) {
                                        Log.e(TAG, "Set local description (answer) failed: $error")
                                        continuation.resumeWith(Result.failure(RuntimeException(error)))
                                    }

                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                }, it)
                            } ?: run {
                                continuation.resumeWith(Result.failure(RuntimeException("Answer SDP is null")))
                            }
                        }

                        override fun onCreateFailure(error: String?) {
                            Log.e(TAG, "Create answer failed: $error")
                            continuation.resumeWith(Result.failure(RuntimeException(error)))
                        }

                        override fun onSetSuccess() {}
                        override fun onSetFailure(p0: String?) {}
                    }, constraints)
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote description (offer) failed: $error")
                    continuation.resumeWith(Result.failure(RuntimeException(error)))
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, remoteDescription)

        } catch (e: Exception) {
            Log.e(TAG, "Create answer exception", e)
            continuation.resumeWith(Result.failure(e))
        }
    }

    suspend fun handleAnswer(answerSdp: String): Result<Unit> = suspendCoroutine { continuation ->
        try {
            Log.i(TAG, "Handling answer...")

            val remoteDescription = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote description (answer) set successfully")
                    continuation.resume(Result.success(Unit))
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Set remote description (answer) failed: $error")
                    continuation.resume(Result.failure(RuntimeException(error)))
                }

                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, remoteDescription)

        } catch (e: Exception) {
            Log.e(TAG, "Handle answer exception", e)
            continuation.resume(Result.failure(e))
        }
    }

    fun addIceCandidate(candidateSdp: String, sdpMid: String?, sdpMLineIndex: Int?) {
        try {
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex ?: 0, candidateSdp)

            peerConnection?.addIceCandidate(iceCandidate)
            Log.d(TAG, "ICE candidate added")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ICE candidate", e)
        }
    }

    fun mute() = audioTrackManager.mute(currentMicrophoneMode)

    fun unmute() = audioTrackManager.unmute(currentMicrophoneMode)

    fun enableSpeaker() = audioRouteManager.enableSpeaker()

    fun disableSpeaker() = audioRouteManager.disableSpeaker()

    suspend fun close() {
        Log.i(TAG, "Closing WebRTC connection...")

        try {
            stopStatsLogging()

            audioTrackManager.release()

            peerConnection?.let { pc ->
                try {
                    pc.senders?.forEach { sender ->
                        try {
                            pc.removeTrack(sender)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error removing sender", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing senders", e)
                }
            }

            peerConnection?.close()

            delay(200)

            videoManager.release()


            remoteVideoTrack = null
            peerConnection = null

            _connectionState.value = ConnectionState.Closed

            Log.i(TAG, "WebRTC connection closed")

        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebRTC connection", e)
        }
    }

    fun cleanup() {
        Log.i(TAG, "Cleaning up WebRTC...")

        microphoneDetector.unregisterListener()

        runBlocking {
            close()
        }

        try {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
            isInitialized = false

            PeerConnectionFactory.stopInternalTracingCapture()
            PeerConnectionFactory.shutdownInternalTracer()

            eglBase.release()

            Log.i(TAG, "WebRTC cleanup complete")

        } catch (e: Exception) {
            Log.e(TAG, "Error during WebRTC cleanup", e)
        }
    }

    private inner class PeerConnectionObserver(
        private val onIceCandidateCallback: (IceCandidate) -> Unit,
        private val onConnectionChangeCallback: (PeerConnection.IceConnectionState) -> Unit,
        private val onAddStreamCallback: (MediaStream) -> Unit,
        private val onRemoveStreamCallback: (MediaStream) -> Unit
    ) : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                Log.d(TAG, "New ICE candidate: ${it.sdp}")
                onIceCandidateCallback(it)
            }
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            newState?.let {
                Log.i(TAG, "ICE connection state: $it")
                onConnectionChangeCallback(it)
            }
        }

        override fun onAddStream(stream: MediaStream?) {
            stream?.let {
                Log.i(TAG, "Remote stream added: ${it.id}")
                onAddStreamCallback(it)
            }
        }

        override fun onRemoveStream(stream: MediaStream?) {
            stream?.let {
                Log.i(TAG, "Remote stream removed: ${it.id}")
                onRemoveStreamCallback(it)
            }
        }

        override fun onDataChannel(dataChannel: DataChannel?) {
            Log.d(TAG, "Data channel: ${dataChannel?.label()}")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "ICE connection receiving: $receiving")
        }

        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering state: $newState")
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state: $newState")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            Log.d(TAG, "ICE candidates removed: ${candidates?.size}")
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            Log.d(TAG, "Track added: ${receiver?.track()?.kind()}")
        }
    }
}
