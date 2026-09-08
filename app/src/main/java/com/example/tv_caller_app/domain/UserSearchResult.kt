package com.example.tv_caller_app.domain

data class UserSearchResult(
    val username: String,
    val contactId: Int,
    val email: String?,
    val avatarUrl: String?
)
