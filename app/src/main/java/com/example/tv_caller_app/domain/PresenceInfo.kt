package com.example.tv_caller_app.domain

data class PresenceInfo(
    val userId: String,
    val contactId: Int,
    val isOnline: Boolean,
    val webrtcStatus: String
)
