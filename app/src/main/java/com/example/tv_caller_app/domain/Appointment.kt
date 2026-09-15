package com.example.tv_caller_app.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A patient appointment returned by the ViKom web backend. */
@Serializable
data class Appointment(
    val id: Int = 0,
    val personnelName: String? = null,
    val date: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val tasks: String? = null,
    val availabilityNotes: String? = null,
    val status: String = "Booked",
    val patientResponseStatus: String = "Pending",
    val patientProposedDate: String? = null,
    val patientProposedStartTime: String? = null,
    val patientProposedEndTime: String? = null
)

fun Appointment.localDate(): LocalDate? =
    runCatching { date?.let(LocalDate::parse) }.getOrNull()

private fun parseTime(value: String?): LocalTime? =
    runCatching { value?.let(LocalTime::parse) }.getOrNull()

fun Appointment.startDateTime(): LocalDateTime? {
    val appointmentDate = localDate() ?: return null
    val appointmentTime = parseTime(startTime) ?: return null
    return LocalDateTime.of(appointmentDate, appointmentTime)
}

fun Appointment.endDateTime(): LocalDateTime? {
    val appointmentDate = localDate() ?: return null
    val appointmentTime = parseTime(endTime) ?: return null
    return LocalDateTime.of(appointmentDate, appointmentTime)
}

fun Appointment.isUpcoming(now: LocalDateTime = LocalDateTime.now()): Boolean =
    (endDateTime() ?: startDateTime())
        ?.let { !it.isBefore(now) }
        ?: false

fun Appointment.canRespond(now: LocalDateTime = LocalDateTime.now()): Boolean =
    patientResponseStatus.equals("Pending", ignoreCase = true) &&
        status.equals("Booked", ignoreCase = true) &&
        startDateTime()?.isAfter(now) == true
