package com.example.tv_caller_app.domain

/**
 * Transport-neutral incoming WebRTC offer. Realtime signaling and FCM both
 * enter the existing call stack through this model.
 */
data class IncomingCallOffer(
    val callId: String = "",
    val callerId: String,
    val callerUserId: String,
    val callerName: String,
    val callerUsername: String,
    val offerSdp: String,
    val contactId: Int? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
    val mediaType: String = "audio"
)
