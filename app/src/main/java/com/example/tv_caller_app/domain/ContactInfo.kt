package com.example.tv_caller_app.domain

data class ContactInfo(
    val id: String,
    val username: String,
    val nickname: String?,
    val contactId: Int,
    val email: String?,
    val isFavorite: Boolean,
    val avatarUrl: String?,
    val isOnline: Boolean = false,
    val prioritySlot: Int = 0
)
