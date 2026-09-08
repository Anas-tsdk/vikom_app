package com.example.tv_caller_app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PrioritySlotDto(
    @SerialName("user_id") val userId: String,
    @SerialName("slot") val slot: Int,
    @SerialName("contact_id") val contactId: String
)
