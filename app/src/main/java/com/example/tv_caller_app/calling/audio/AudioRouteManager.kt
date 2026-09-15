package com.example.tv_caller_app.calling.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

class AudioRouteManager(context: Context) {

    companion object {
        private const val TAG = "AudioRouteManager"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedVolume: Int = -1
    var isSpeakerOn = false
        private set

    fun startCallAudio(preferSpeaker: Boolean = false) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (preferSpeaker) enableSpeaker() else disableSpeaker()
            Log.d(TAG, "Call audio started: MODE_IN_COMMUNICATION, speaker=$preferSpeaker")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start call audio", e)
        }
    }

    fun stopCallAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
            if (savedVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0)
                savedVolume = -1
            }
            isSpeakerOn = false
            Log.d(TAG, "Call audio stopped: MODE_NORMAL")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop call audio", e)
        }
    }

    fun enableSpeaker() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                val result = audioManager.setCommunicationDevice(speaker)
                Log.i(TAG, "Speaker enabled (modern API), result=$result")
            } else {
                Log.w(TAG, "Built-in speaker not found in available devices")
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
            Log.i(TAG, "Speaker enabled (legacy API)")
        }
        val voiceStream = AudioManager.STREAM_VOICE_CALL
        if (savedVolume < 0) savedVolume = audioManager.getStreamVolume(voiceStream)
        val maxVolume = audioManager.getStreamMaxVolume(voiceStream)
        // A high but not clipped default is clearer than forcing every device to
        // 100%. The physical volume keys remain available during the call.
        val clearVoiceVolume = (maxVolume * 0.85f).toInt().coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(voiceStream)
        if (currentVolume < clearVoiceVolume) {
            audioManager.setStreamVolume(voiceStream, clearVoiceVolume, 0)
        }
        Log.i(TAG, "Speaker volume prepared: $currentVolume -> ${audioManager.getStreamVolume(voiceStream)}")
        isSpeakerOn = true
    }

    fun disableSpeaker() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val earpiece = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
            if (earpiece != null) {
                val result = audioManager.setCommunicationDevice(earpiece)
                Log.i(TAG, "Speaker disabled - routed to earpiece (modern API), result=$result")
            } else {
                audioManager.clearCommunicationDevice()
                Log.i(TAG, "Speaker disabled - no earpiece found, cleared device (modern API)")
            }
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            Log.i(TAG, "Speaker disabled (legacy API)")
        }
        if (savedVolume >= 0) {
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0)
            Log.i(TAG, "Speaker volume restored to $savedVolume")
            savedVolume = -1
        }
        isSpeakerOn = false
    }
}
