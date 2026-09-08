package com.example.tv_caller_app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val username: String? = null,
    val email: String? = null,

    @SerialName("contact_id")
    val contactId: Int,

    @SerialName("is_online")
    val isOnline: Boolean = false,

    @SerialName("last_seen")
    val lastSeen: String? = null,

    @SerialName("device_type")
    val deviceType: String? = "unknown",

    @SerialName("webrtc_status")
    val webrtcStatus: String = "offline",

    @SerialName("avatar_url")
    val avatarUrl: String? = null,

    @SerialName("has_seen_onboarding")
    val hasSeenOnboarding: Boolean = false,

    @SerialName("created_at")
    val createdAt: String? = null,

    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class ProfileInsert(
    val id: String,
    val username: String? = null,
    val email: String? = null
)

@Serializable
data class ProfilePresenceUpdate(
    @SerialName("is_online")
    val isOnline: Boolean,

    @SerialName("last_seen")
    val lastSeen: String,

    @SerialName("webrtc_status")
    val webrtcStatus: String,

    @SerialName("device_type")
    val deviceType: String? = null
)
