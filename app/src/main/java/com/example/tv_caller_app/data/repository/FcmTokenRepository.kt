package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stores this device's Firebase token using the signed-in user's Supabase session. */
class FcmTokenRepository private constructor(
    private val sessionManager: SessionManager
) {
    companion object {
        private const val TAG = "FcmTokenRepository"

        @Volatile private var instance: FcmTokenRepository? = null

        fun getInstance(sessionManager: SessionManager): FcmTokenRepository =
            instance ?: synchronized(this) {
                instance ?: FcmTokenRepository(sessionManager).also { instance = it }
            }
    }

    suspend fun updateToken(token: String): Result<Unit> {
        if (token.isBlank()) {
            return Result.failure(IllegalArgumentException("FCM token must not be blank"))
        }
        val userId = sessionManager.getUserId()
            ?: return Result.failure(IllegalStateException("Cannot register push token while signed out"))
        if (!sessionManager.isLoggedIn()) {
            return Result.failure(IllegalStateException("Cannot register push token without a user session"))
        }

        return runCatching {
            // SupabaseClient is configured with the public anon/publishable key;
            // row-level security and the restored user JWT authorize this update.
            SupabaseClient.client.from("profiles").update(
                buildJsonObject { put("fcm_token", token) }
            ) {
                filter { eq("id", userId) }
            }
            Log.i(TAG, "Push token registered for the signed-in profile")
            Unit
        }.onFailure { error ->
            // Supabase exceptions include request headers; logging the full
            // exception would put the signed-in user's bearer token in logcat.
            Log.e(TAG, "Unable to register push token (${error::class.simpleName})")
        }
    }

    /** Removes this device from the signed-in profile before logout. */
    suspend fun clearToken(): Result<Unit> {
        val userId = sessionManager.getUserId()
            ?: return Result.success(Unit)
        if (!sessionManager.isLoggedIn()) return Result.success(Unit)

        return runCatching {
            SupabaseClient.client.from("profiles").update(
                buildJsonObject { put("fcm_token", JsonNull) }
            ) {
                filter { eq("id", userId) }
            }
            Log.i(TAG, "Push token removed from the signed-in profile")
            Unit
        }.onFailure { error ->
            // Logging out must remain possible even when the device is offline.
            Log.w(TAG, "Unable to remove push token before logout (${error::class.simpleName})")
        }
    }
}
