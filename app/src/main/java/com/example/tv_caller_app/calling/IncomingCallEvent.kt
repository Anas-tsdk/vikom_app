package com.example.tv_caller_app.calling

data class IncomingCallEvent(
    val callerId: String,
    val callerName: String,
    val nickname: String?,
    val avatarUrl: String?,
    val mediaType: String = "audio"
)
