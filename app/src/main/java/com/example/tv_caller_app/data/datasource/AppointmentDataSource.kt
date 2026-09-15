package com.example.tv_caller_app.data.datasource

import com.example.tv_caller_app.domain.Appointment

interface AppointmentDataSource {
    suspend fun getMyAppointments(forceRefresh: Boolean = false): List<Appointment>
    suspend fun respondToInvitation(
        appointmentId: Int,
        response: AppointmentResponse,
        proposal: AppointmentRescheduleProposal? = null
    ): Appointment
    fun invalidateCache()
}

data class AppointmentRescheduleProposal(
    val date: String,
    val startTime: String,
    val endTime: String
)

enum class AppointmentResponse(val apiValue: String) {
    ACCEPTED("Accepted"),
    DECLINED("Declined")
}

class ProfileNotLinkedException : Exception("No patient is linked to this Supabase profile")
class SessionExpiredException : Exception("The Supabase session has expired")
class BackendNotConfiguredException : Exception("backend.base.url is not configured")
class BackendRequestException(message: String) : Exception(message)
