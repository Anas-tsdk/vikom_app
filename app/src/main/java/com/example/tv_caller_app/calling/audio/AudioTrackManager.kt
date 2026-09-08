package com.example.tv_caller_app.calling.audio

import android.util.Log
import com.example.tv_caller_app.calling.webrtc.MicrophoneMode
import com.example.tv_caller_app.calling.webrtc.WebRTCConfig
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnectionFactory

class AudioTrackManager {

    companion object {
        private const val TAG = "AudioTrackManager"
    }

    private var audioSource: AudioSource? = null
    var localAudioTrack: AudioTrack? = null
        private set
    var remoteAudioTrack: AudioTrack? = null
        private set
    var isMuted = false
        private set

    fun createAudioTrack(
        factory: PeerConnectionFactory,
        micMode: MicrophoneMode,
        debugDisabled: Boolean,
        onModeChanged: ((MicrophoneMode, String) -> Unit)?
    ): AudioTrack? {
        if (debugDisabled) {
            Log.w(TAG, "Debug mode: Skipping audio track creation")
            return null
        }
        if (micMode == MicrophoneMode.RECEIVE_ONLY) {
            Log.w(TAG, "Skipping audio track creation - RECEIVE_ONLY mode (no microphone)")
            return null
        }
        return try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", WebRTCConfig.AudioConstraints.ECHO_CANCELLATION.toString()))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", WebRTCConfig.AudioConstraints.AUTO_GAIN_CONTROL.toString()))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", WebRTCConfig.AudioConstraints.HIGH_PASS_FILTER.toString()))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", WebRTCConfig.AudioConstraints.NOISE_SUPPRESSION.toString()))
                mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", WebRTCConfig.AudioConstraints.TYPED_NOISE_DETECTION.toString()))
            }
            audioSource = factory.createAudioSource(constraints)
            localAudioTrack = factory.createAudioTrack(WebRTCConfig.StreamLabels.AUDIO_TRACK_ID, audioSource)
            Log.d(TAG, "Audio track created successfully")
            localAudioTrack
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create audio track", e)
            onModeChanged?.invoke(MicrophoneMode.RECEIVE_ONLY, "Failed to access microphone. Switched to receive-only mode.")
            null
        }
    }

    fun mute(micMode: MicrophoneMode) {
        if (micMode == MicrophoneMode.RECEIVE_ONLY) {
            Log.w(TAG, "Cannot mute - RECEIVE_ONLY mode (no microphone)")
            return
        }
        localAudioTrack?.setEnabled(false)
        isMuted = true
        Log.i(TAG, "Audio muted")
    }

    fun unmute(micMode: MicrophoneMode) {
        if (micMode == MicrophoneMode.RECEIVE_ONLY) {
            Log.w(TAG, "Cannot unmute - RECEIVE_ONLY mode (no microphone)")
            return
        }
        localAudioTrack?.setEnabled(true)
        isMuted = false
        Log.i(TAG, "Audio unmuted")
    }

    fun onRemoteStream(stream: MediaStream) {
        if (stream.audioTracks.isNotEmpty()) {
            remoteAudioTrack = stream.audioTracks[0]
            remoteAudioTrack?.setEnabled(true)
            Log.d(TAG, "Remote audio track enabled")
        }
    }

    fun onRemoveStream() {
        remoteAudioTrack = null
    }

    fun release() {
        localAudioTrack?.setEnabled(false)
        remoteAudioTrack?.setEnabled(false)
        try { localAudioTrack?.dispose() } catch (e: Exception) { Log.w(TAG, "Error disposing local audio track", e) }
        try { remoteAudioTrack?.dispose() } catch (e: Exception) { Log.w(TAG, "Error disposing remote audio track", e) }
        try { audioSource?.dispose() } catch (e: Exception) { Log.w(TAG, "Error disposing audio source", e) }
        localAudioTrack = null
        remoteAudioTrack = null
        audioSource = null
        isMuted = false
    }
}
