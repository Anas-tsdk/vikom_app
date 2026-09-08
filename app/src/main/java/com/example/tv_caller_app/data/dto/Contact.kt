package com.example.tv_caller_app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Contact(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("contact_id")
    val contactId: Int,
    val username: String? = null,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val nickname: String? = null,
    @SerialName("is_favorite")
    val isFavorite: Boolean = false,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class ContactInsert(
    @SerialName("user_id")
    val userId: String,
    @SerialName("contact_id")
    val contactId: Int,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val nickname: String? = null,
    @SerialName("is_favorite")
    val isFavorite: Boolean = false
)

@Serializable
data class ContactUpdate(
    @SerialName("contact_id")
    val contactId: Int,
    val email: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val nickname: String? = null,
    @SerialName("is_favorite")
    val isFavorite: Boolean = false
)
