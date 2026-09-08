package com.example.tv_caller_app.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CallHistory(
    @SerialName("id")
    val id: String,

    @SerialName("user_id")
    val userId: String,

    @SerialName("contact_id")
    val contactId: Int? = null,

    @SerialName("contact_name")
    val contactName: String? = null,

    @SerialName("call_type")
    val callType: String,

    @SerialName("call_duration")
    val callDuration: Int = 0,

    @SerialName("call_timestamp")
    val callTimestamp: String? = null,

    @SerialName("notes")
    val notes: String? = null,

    @SerialName("call_method")
    val callMethod: String = "webrtc",

    @SerialName("media_type")
    val mediaType: String = "audio",

    @SerialName("connection_quality")
    val connectionQuality: String? = null,

    @SerialName("ice_connection_state")
    val iceConnectionState: String? = null,

    @SerialName("end_reason")
    val endReason: String? = null,

    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class CallHistoryInsert(
    @SerialName("user_id")
    val userId: String,

    @SerialName("contact_id")
    val contactId: Int? = null,

    @SerialName("contact_name")
    val contactName: String? = null,

    @SerialName("call_type")
    val callType: String,

    @SerialName("call_duration")
    val callDuration: Int = 0,

    @SerialName("end_reason")
    val endReason: String? = null,

    @SerialName("call_method")
    val callMethod: String = "webrtc",

    @SerialName("media_type")
    val mediaType: String = "audio"
)
