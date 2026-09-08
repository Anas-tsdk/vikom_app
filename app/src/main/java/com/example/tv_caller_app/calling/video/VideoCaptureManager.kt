package com.example.tv_caller_app.calling.video

import android.content.Context
import android.util.Log
import com.example.tv_caller_app.calling.webrtc.WebRTCConfig
import com.example.tv_caller_app.calling.permissions.PermissionHelper
import org.webrtc.*

class VideoCaptureManager(
    private val context: Context,
    private val eglBase: EglBase
) {

    companion object {
        private const val TAG = "VideoCaptureManager"
    }

    var onLocalVideoTrack: ((VideoTrack) -> Unit)? = null

    private var videoEnabled = false
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun hasCamera(): Boolean = PermissionHelper.hasCameraHardware(context)

    fun setVideoEnabled(enabled: Boolean) {
        videoEnabled = enabled
        Log.d(TAG, "Video enabled: $enabled")
    }

    fun createVideoTrack(factory: PeerConnectionFactory): VideoTrack? {
        val capturer = createVideoCapturer() ?: run {
            Log.d(TAG, "No camera available — skipping video track")
            return null
        }
        videoCapturer = capturer
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(capturer.isScreencast)
        capturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

        localVideoTrack = factory.createVideoTrack(
            WebRTCConfig.StreamLabels.VIDEO_TRACK_ID,
            videoSource
        )
        localVideoTrack?.setEnabled(videoEnabled)

        if (videoEnabled) {
            capturer.startCapture(
                WebRTCConfig.VideoConstraints.WIDTH,
                WebRTCConfig.VideoConstraints.HEIGHT,
                WebRTCConfig.VideoConstraints.FPS
            )
            localVideoTrack?.let { onLocalVideoTrack?.invoke(it) }
            Log.d(TAG, "Video track created and capture started")
        } else {
            Log.d(TAG, "Video track created (capture deferred — camera disabled for this call)")
        }

        return localVideoTrack
    }

    fun startCapture() {
        try {
            if (videoCapturer == null) {
                Log.w(TAG, "startCapture called but capturer is null — no camera available")
                return
            }
            videoCapturer?.startCapture(
                WebRTCConfig.VideoConstraints.WIDTH,
                WebRTCConfig.VideoConstraints.HEIGHT,
                WebRTCConfig.VideoConstraints.FPS
            )
            localVideoTrack?.setEnabled(true)
            localVideoTrack?.let { onLocalVideoTrack?.invoke(it) }
            Log.d(TAG, "Video capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video capture", e)
        }
    }

    fun stopCapture() {
        try {
            videoCapturer?.stopCapture()
            localVideoTrack?.setEnabled(false)
            Log.d(TAG, "Video capture stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop video capture", e)
        }
    }

    fun release() {
        try { videoCapturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "Error stopping capturer", e) }
        try { videoCapturer?.dispose() } catch (e: Exception) { Log.w(TAG, "Error disposing capturer", e) }
        try { localVideoTrack?.dispose() } catch (e: Exception) { Log.w(TAG, "Error disposing video track", e) }
        try { videoSource?.dispose() } catch (e: Exception) { Log.w(TAG, "Error disposing video source", e) }
        try { surfaceTextureHelper?.dispose() } catch (e: Exception) { Log.w(TAG, "Error disposing surface texture helper", e) }

        videoCapturer = null
        videoSource = null
        localVideoTrack = null
        surfaceTextureHelper = null
        videoEnabled = false
        Log.d(TAG, "Video resources released")
    }


    private fun createVideoCapturer(): VideoCapturer? {
        if (!hasCamera()) return null
        val enumerator = Camera2Enumerator(context)
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return enumerator.deviceNames.firstOrNull()?.let {
            enumerator.createCapturer(it, null)
        }
    }
}
