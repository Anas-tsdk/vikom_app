package com.example.tv_caller_app.calling.video

import android.content.Context
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
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
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var currentCameraIsFrontFacing = true

    fun hasCameraHardware(): Boolean = PermissionHelper.hasCameraHardware(context)

    fun hasCamera(): Boolean = hasCameraHardware() &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasMultipleCameras(): Boolean = hasCamera() && cameraEnumerator().deviceNames.size > 1

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

    /** Switches between the front and rear cameras without renegotiating the call. */
    fun switchCamera(onComplete: (success: Boolean, frontFacing: Boolean) -> Unit) {
        val capturer = videoCapturer
        if (capturer == null || !hasMultipleCameras()) {
            onComplete(false, currentCameraIsFrontFacing)
            return
        }

        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                currentCameraIsFrontFacing = isFrontCamera
                Log.i(TAG, "Camera switched; frontFacing=$isFrontCamera")
                onComplete(true, isFrontCamera)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "Could not switch camera: $errorDescription")
                onComplete(false, currentCameraIsFrontFacing)
            }
        })
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
        currentCameraIsFrontFacing = true
        Log.d(TAG, "Video resources released")
    }


    private fun createVideoCapturer(): CameraVideoCapturer? {
        if (!hasCamera()) {
            Log.w(TAG, "Camera hardware or CAMERA permission is unavailable")
            return null
        }
        val enumerator = cameraEnumerator()
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)?.also {
                    currentCameraIsFrontFacing = true
                }
            }
        }
        return enumerator.deviceNames.firstOrNull()?.let {
            enumerator.createCapturer(it, null)?.also { _ ->
                currentCameraIsFrontFacing = enumerator.isFrontFacing(it)
            }
        }
    }

    private fun cameraEnumerator(): CameraEnumerator =
        if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            // Several older tablets expose only the Camera1 API. Falling back
            // keeps video and camera switching available on those devices.
            Camera1Enumerator(true)
        }
}
