package com.example.tv_caller_app.domain

import kotlinx.serialization.Serializable

/** A lightweight appointment change delivered over the existing realtime channel. */
@Serializable
data class AppointmentEvent(
    val appointmentId: Int = 0,
    val action: String = "updated",
    val date: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val personnelName: String = "",
    val status: String = "Booked",
    val patientResponseStatus: String = "Pending",
    val shortMessage: String = ""
)
