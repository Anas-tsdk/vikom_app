package com.example.tv_caller_app.data.repository

import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

@Serializable
data class DailyCheckInStatus(
    val required: Boolean = false,
    val checkedInToday: Boolean = false,
    @SerialName("lastCheckInAt") val lastCheckInAt: String? = null,
    @SerialName("lastCheckInSource") val lastCheckInSource: String? = null
)

@Serializable
private data class CloudDailyCheckIn(
    @SerialName("profile_id") val profileId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("last_check_in_at") val lastCheckInAt: String? = null,
    @SerialName("last_check_in_source") val lastCheckInSource: String? = null
)

class DailyCheckInRepository private constructor(
    private val sessionManager: SessionManager
) {
    companion object {
        private const val CARE_TIME_ZONE = "Europe/Oslo"

        @Volatile private var instance: DailyCheckInRepository? = null

        fun getInstance(sessionManager: SessionManager): DailyCheckInRepository =
            instance ?: synchronized(this) {
                instance ?: DailyCheckInRepository(sessionManager).also { instance = it }
            }
    }

    private val supabase = SupabaseClient.client

    /**
     * Reads the cloud check-in row directly from Supabase. A row is created by
     * the web backend only for accounts linked to web patients; no row therefore
     * means that this account is not subject to the daily gate.
     */
    suspend fun getStatus(): Result<DailyCheckInStatus> = runCatching {
        val userId = sessionManager.getUserId()
            ?: throw IllegalStateException("Cannot check daily status while signed out")
        val row = supabase.from("patient_daily_check_ins")
            .select(
                columns = Columns.list(
                    "profile_id",
                    "started_at",
                    "last_check_in_at",
                    "last_check_in_source"
                )
            ) {
                filter { eq("profile_id", userId) }
                limit(1)
            }
            .decodeList<CloudDailyCheckIn>()
            .firstOrNull()

        row?.toStatus() ?: DailyCheckInStatus(required = false)
    }

    suspend fun confirmToday(): Result<DailyCheckInStatus> = runCatching {
        if (sessionManager.getUserId() == null) {
            throw IllegalStateException("Cannot confirm daily status while signed out")
        }

        // The database function uses auth.uid() and now(), so clients cannot
        // confirm another patient or forge the server timestamp.
        supabase.postgrest.rpc("confirm_daily_check_in")
        getStatus().getOrThrow()
    }

    private fun CloudDailyCheckIn.toStatus(): DailyCheckInStatus {
        val zone = ZoneId.of(CARE_TIME_ZONE)
        val checkedInToday = lastCheckInAt
            ?.let(::parseInstant)
            ?.atZone(zone)
            ?.toLocalDate() == LocalDate.now(zone)

        return DailyCheckInStatus(
            required = true,
            checkedInToday = checkedInToday,
            lastCheckInAt = lastCheckInAt,
            lastCheckInSource = lastCheckInSource
        )
    }

    private fun parseInstant(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
}
