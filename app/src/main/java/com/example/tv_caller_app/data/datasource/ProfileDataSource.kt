package com.example.tv_caller_app.data.datasource

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.dto.Profile
import com.example.tv_caller_app.data.dto.ProfileInsert
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class ProfileDataSource private constructor(
    private val sessionManager: SessionManager
) {

    private val supabase = SupabaseClient.client

    companion object {
        private const val TAG = "ProfileDataSource"

        @Volatile private var instance: ProfileDataSource? = null
        fun getInstance(sessionManager: SessionManager): ProfileDataSource =
            instance ?: synchronized(this) { instance ?: ProfileDataSource(sessionManager).also { instance = it } }
    }

    private fun getUserId(): String =
        sessionManager.getUserId() ?: throw IllegalStateException("User not logged in")

    fun getStoredEmail(): String? = sessionManager.getEmail()


    suspend fun getProfile(): Profile? {
        val profile = supabase.from("profiles")
            .select { filter { eq("id", getUserId()) } }
            .decodeList<Profile>()
            .firstOrNull() ?: return null
        profile.username?.let { sessionManager.saveUsername(it) }
        return profile
    }

    suspend fun profileExists(userId: String): Boolean =
        supabase.from("profiles")
            .select { filter { eq("id", userId) } }
            .decodeSingleOrNull<ProfileInsert>() != null

    suspend fun insertProfile(userId: String, username: String, email: String) {
        supabase.from("profiles").insert(
            ProfileInsert(id = userId, username = username, email = email)
        )
    }

    suspend fun isUsernameTaken(username: String): Boolean {
        val userId = getUserId()

        @Serializable
        data class IdRow(val id: String)

        return supabase.from("profiles")
            .select(columns = Columns.list("id")) {
                filter {
                    eq("username", username)
                    neq("id", userId)
                }
            }
            .decodeList<IdRow>()
            .isNotEmpty()
    }

    suspend fun updateUsername(username: String) {
        val update = buildJsonObject { put("username", username) }
        supabase.from("profiles")
            .update(update) { filter { eq("id", getUserId()) } }
        sessionManager.saveUsername(username)
    }

    suspend fun updateAvatarUrl(url: String?) {
        val update = buildJsonObject { put("avatar_url", url) }
        supabase.from("profiles")
            .update(update) { filter { eq("id", getUserId()) } }
    }

    suspend fun getHasSeenOnboarding(): Boolean =
        supabase.from("profiles")
            .select { filter { eq("id", getUserId()) } }
            .decodeList<Profile>()
            .firstOrNull()?.hasSeenOnboarding ?: false

    suspend fun markOnboardingComplete() {
        val update = buildJsonObject { put("has_seen_onboarding", true) }
        supabase.from("profiles")
            .update(update) { filter { eq("id", getUserId()) } }
    }



    suspend fun getProfilesForContactIds(contactIds: List<Int>): Map<Int, Profile> {
        if (contactIds.isEmpty()) return emptyMap()
        return supabase.from("profiles")
            .select { filter { isIn("contact_id", contactIds) } }
            .decodeList<Profile>()
            .associateBy { it.contactId }
    }



    suspend fun searchAllUsers(query: String): List<Profile> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val numericQuery = trimmed.toLongOrNull()
        return supabase.from("profiles")
            .select {
                filter {
                    neq("id", getUserId())
                    if (numericQuery != null) {
                        or {
                            ilike("username", "%$trimmed%")
                            eq("contact_id", numericQuery)
                        }
                    } else {
                        ilike("username", "%$trimmed%")
                    }
                }
                limit(20)
            }
            .decodeList<Profile>()
    }



    suspend fun updateWebRTCStatus(status: String) {
        val update = buildJsonObject {
            put("webrtc_status", status)
            put("last_seen", Instant.now().toString())
        }
        supabase.from("profiles")
            .update(update) { filter { eq("id", getUserId()) } }
    }

    suspend fun updateLastSeen() {
        val update = buildJsonObject { put("last_seen", Instant.now().toString()) }
        supabase.from("profiles")
            .update(update) { filter { eq("id", getUserId()) } }
        Log.d(TAG, "Heartbeat sent")
    }

}
