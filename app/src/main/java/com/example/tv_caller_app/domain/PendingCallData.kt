package com.example.tv_caller_app.domain

data class PendingCallData(
    val callerId: String,
    val callerName: String,
    val nickname: String?,
    val avatarUrl: String?,
    val mediaType: String = "audio"
)
