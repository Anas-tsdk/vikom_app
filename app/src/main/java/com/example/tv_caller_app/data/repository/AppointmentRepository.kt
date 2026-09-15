package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.datasource.AppointmentDataSource
import com.example.tv_caller_app.data.datasource.AppointmentResponse
import com.example.tv_caller_app.data.datasource.AppointmentRescheduleProposal
import com.example.tv_caller_app.data.datasource.BackendNotConfiguredException
import com.example.tv_caller_app.data.datasource.ProfileNotLinkedException
import com.example.tv_caller_app.data.datasource.SessionExpiredException
import com.example.tv_caller_app.data.remote.AuthenticatedBackendClient
import com.example.tv_caller_app.data.remote.BackendApiClient
import com.example.tv_caller_app.domain.Appointment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable

class AppointmentRepository private constructor(
    private val sessionManager: SessionManager,
    private val authRepository: AuthRepository
) : AppointmentDataSource {
    private var cachedAppointments: List<Appointment>? = null
    private var cacheTimestamp = 0L

    companion object {
        private const val TAG = "AppointmentRepository"
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L
        private const val MINE_PATH = "api/tv/appointments/mine"

        @Volatile private var instance: AppointmentRepository? = null

        fun getInstance(
            sessionManager: SessionManager,
            authRepository: AuthRepository
        ): AppointmentRepository = instance ?: synchronized(this) {
            instance ?: AppointmentRepository(sessionManager, authRepository).also { instance = it }
        }
    }

    override suspend fun getMyAppointments(forceRefresh: Boolean): List<Appointment> {
        val cached = cachedAppointments
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cacheTimestamp < CACHE_DURATION_MS) {
            return cached
        }

        return try {
            val appointments = withContext(Dispatchers.IO) {
                BackendApiClient.json.decodeFromString<List<Appointment>>(
                    AuthenticatedBackendClient.get(MINE_PATH, sessionManager, authRepository)
                )
            }
            cachedAppointments = appointments
            cacheTimestamp = System.currentTimeMillis()
            appointments
        } catch (error: Exception) {
            if (error is ProfileNotLinkedException ||
                error is SessionExpiredException ||
                error is BackendNotConfiguredException
            ) throw error

            Log.e(TAG, "Unable to refresh appointments", error)
            cached?.let { return it }
            throw error
        }
    }

    override suspend fun respondToInvitation(
        appointmentId: Int,
        response: AppointmentResponse,
        proposal: AppointmentRescheduleProposal?
    ): Appointment = withContext(Dispatchers.IO) {
        val body = BackendApiClient.json.encodeToString(
            AppointmentResponseBody(
                response = response.apiValue,
                proposedDate = proposal?.date,
                proposedStartTime = proposal?.startTime,
                proposedEndTime = proposal?.endTime
            )
        )
        val updated = BackendApiClient.json.decodeFromString<Appointment>(
            AuthenticatedBackendClient.postJson(
                "api/tv/appointments/$appointmentId/response",
                body,
                sessionManager,
                authRepository
            )
        )

        cachedAppointments = cachedAppointments?.map {
            if (it.id == updated.id) updated else it
        }
        cacheTimestamp = System.currentTimeMillis()
        updated
    }

    override fun invalidateCache() {
        cachedAppointments = null
        cacheTimestamp = 0L
    }

    @Serializable
    private data class AppointmentResponseBody(
        val response: String,
        val proposedDate: String? = null,
        val proposedStartTime: String? = null,
        val proposedEndTime: String? = null
    )
}
