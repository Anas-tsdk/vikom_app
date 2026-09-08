package com.example.tv_caller_app.data.datasource

import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.dto.Profile
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit

class PresenceDataSource private constructor(
    private val sessionManager: SessionManager
) {

    companion object {
        @Volatile private var instance: PresenceDataSource? = null
        fun getInstance(sessionManager: SessionManager): PresenceDataSource =
            instance ?: synchronized(this) { instance ?: PresenceDataSource(sessionManager).also { instance = it } }
    }

    private val supabase = SupabaseClient.client

    private val presenceColumns = Columns.list(
        "id", "username", "email", "contact_id",
        "is_online", "last_seen", "device_type", "webrtc_status",
        "created_at", "updated_at"
    )

    private fun getUserId(): String =
        sessionManager.getUserId() ?: throw IllegalStateException("User not logged in")

    suspend fun setOnlineStatus(deviceType: String) {
        supabase.postgrest.rpc("set_user_online", buildJsonObject {
            put("user_uuid", getUserId())
            put("p_device_type", deviceType)
        })
    }

    suspend fun setOfflineStatus() {
        supabase.postgrest.rpc("set_user_offline", buildJsonObject {
            put("user_uuid", getUserId())
        })
    }

    suspend fun getOnlineContactIds(): Set<Int> {
        val userId = getUserId()
        val threshold = Instant.now().minus(90, ChronoUnit.SECONDS).toString()
        return supabase.from("profiles")
            .select(columns = presenceColumns) {
                filter {
                    eq("is_online", true)
                    gte("last_seen", threshold)
                    neq("id", userId)
                }
            }
            .decodeList<Profile>()
            .map { it.contactId }
            .toSet()
    }

    suspend fun getUserPresence(userId: String): Profile =
        supabase.from("profiles")
            .select(columns = presenceColumns) { filter { eq("id", userId) } }
            .decodeSingle<Profile>()

    suspend fun getUserPresenceByContactId(contactId: Int): Profile =
        supabase.from("profiles")
            .select(columns = presenceColumns) { filter { eq("contact_id", contactId) } }
            .decodeSingle<Profile>()
}
