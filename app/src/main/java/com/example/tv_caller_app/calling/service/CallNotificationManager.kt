package com.example.tv_caller_app.calling.service

import android.content.Context

interface CallNotificationDataSource {
    fun startService(contactName: String, isIncoming: Boolean, mediaType: String)
    fun stopService()
    fun updateCallDuration(durationSeconds: Long, isMuted: Boolean, isSpeakerOn: Boolean)
}

class CallNotificationManager(private val context: Context) : CallNotificationDataSource {

    override fun startService(contactName: String, isIncoming: Boolean, mediaType: String) {
        CallService.startService(context, contactName, isIncoming, mediaType)
    }

    override fun stopService() {
        CallService.stopService(context)
    }

    override fun updateCallDuration(durationSeconds: Long, isMuted: Boolean, isSpeakerOn: Boolean) {
        CallService.updateCallDuration(context, durationSeconds, isMuted, isSpeakerOn)
    }
}
