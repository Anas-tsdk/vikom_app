package com.example.tv_caller_app.calling.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager

class MicrophoneDetector(context: Context) {

    sealed class MicSource {
        object None : MicSource() {
            override fun toString() = "None"
        }
        object BuiltIn : MicSource() {
            override fun toString() = "BuiltIn"
        }
        data class External(val typeName: String, val productName: String) : MicSource() {
            override fun toString() = "External($typeName '$productName')"
        }
        data class Both(val externalTypeName: String, val externalProductName: String) : MicSource() {
            override fun toString() = "Both(BuiltIn + External($externalTypeName '$externalProductName'))"
        }
    }

    companion object {
        private const val TAG = "MicrophoneDetector"

        private val MIC_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET
        )
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var deviceCallback: AudioDeviceCallback? = null


    fun hasMicrophoneDevice(): Boolean = getActiveMicSource() != MicSource.None

    fun getActiveMicSource(): MicSource {
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val micDevices = allInputs.filter { it.type in MIC_TYPES }

        val hasBuiltIn = micDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val external   = micDevices.firstOrNull { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }

        return when {
            micDevices.isEmpty()            -> MicSource.None
            hasBuiltIn && external != null  -> MicSource.Both(typeName(external.type), external.productName.toString())
            external != null                -> MicSource.External(typeName(external.type), external.productName.toString())
            else                            -> MicSource.BuiltIn
        }
    }


    fun registerListener(onChanged: (hasMic: Boolean, source: MicSource) -> Unit) {
        unregisterListener()
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                val source = getActiveMicSource()
                onChanged(source != MicSource.None, source)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val source = getActiveMicSource()
                onChanged(source != MicSource.None, source)
            }
        }
        audioManager.registerAudioDeviceCallback(cb, null)
        deviceCallback = cb
    }

    fun unregisterListener() {
        deviceCallback?.let {
            audioManager.unregisterAudioDeviceCallback(it)
            deviceCallback = null
        }
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC       -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_USB_DEVICE        -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET       -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO     -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADSET     -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE  -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER   -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP    -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI              -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG       -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL      -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_TELEPHONY         -> "TELEPHONY"
        AudioDeviceInfo.TYPE_FM_TUNER          -> "FM_TUNER"
        AudioDeviceInfo.TYPE_AUX_LINE          -> "AUX_LINE"
        else                                   -> "UNKNOWN($type)"
    }
}
