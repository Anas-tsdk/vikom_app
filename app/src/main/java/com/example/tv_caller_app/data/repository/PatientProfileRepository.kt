package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.remote.AuthenticatedBackendClient
import com.example.tv_caller_app.data.remote.BackendApiClient
import com.example.tv_caller_app.domain.CareTeamMember
import com.example.tv_caller_app.domain.PatientProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PatientProfileRepository private constructor(
    private val sessionManager: SessionManager,
    private val authRepository: AuthRepository
) {
    private var cachedProfile: PatientProfile? = null
    private var profileTimestamp = 0L
    private var cachedCareTeam: List<CareTeamMember>? = null
    private var careTeamTimestamp = 0L

    companion object {
        private const val TAG = "PatientProfileRepo"
        private const val CACHE_DURATION_MS = 30 * 60 * 1000L

        @Volatile private var instance: PatientProfileRepository? = null

        fun getInstance(
            sessionManager: SessionManager,
            authRepository: AuthRepository
        ): PatientProfileRepository = instance ?: synchronized(this) {
            instance ?: PatientProfileRepository(sessionManager, authRepository).also { instance = it }
        }
    }

    suspend fun getMyProfile(forceRefresh: Boolean = false): PatientProfile {
        if (!forceRefresh && isFresh(profileTimestamp)) cachedProfile?.let { return it }
        return withContext(Dispatchers.IO) {
            BackendApiClient.json.decodeFromString<PatientProfile>(
                AuthenticatedBackendClient.get("api/tv/me", sessionManager, authRepository)
            ).also {
                cachedProfile = it
                profileTimestamp = System.currentTimeMillis()
            }
        }
    }

    suspend fun fullNameOrNull(): String? =
        runCatching { getMyProfile().fullName.takeIf(String::isNotBlank) }
            .onFailure { Log.w(TAG, "Could not load patient name: ${it.message}") }
            .getOrNull()

    suspend fun getMyCareTeam(forceRefresh: Boolean = false): List<CareTeamMember> {
        if (!forceRefresh && isFresh(careTeamTimestamp)) cachedCareTeam?.let { return it }
        return withContext(Dispatchers.IO) {
            BackendApiClient.json.decodeFromString<List<CareTeamMember>>(
                AuthenticatedBackendClient.get("api/tv/careteam/mine", sessionManager, authRepository)
            ).also {
                cachedCareTeam = it
                careTeamTimestamp = System.currentTimeMillis()
            }
        }
    }

    fun invalidateCache() {
        cachedProfile = null
        cachedCareTeam = null
        profileTimestamp = 0L
        careTeamTimestamp = 0L
    }

    private fun isFresh(timestamp: Long): Boolean =
        timestamp > 0 && System.currentTimeMillis() - timestamp < CACHE_DURATION_MS
}

