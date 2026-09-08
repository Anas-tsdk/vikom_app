package com.example.tv_caller_app.data.datasource

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.user.UserSession
import io.github.jan.supabase.storage.storage

class AvatarDataSource private constructor(
    private val sessionManager: SessionManager
) {

    private val supabase = SupabaseClient.client

    companion object {
        private const val TAG = "AvatarDataSource"

        @Volatile private var instance: AvatarDataSource? = null
        fun getInstance(sessionManager: SessionManager): AvatarDataSource =
            instance ?: synchronized(this) { instance ?: AvatarDataSource(sessionManager).also { instance = it } }
    }

    private fun getUserId(): String =
        sessionManager.getUserId() ?: throw IllegalStateException("User not logged in")

    private suspend fun ensureSession() {
        if (supabase.auth.currentSessionOrNull() != null) return
        val accessToken = sessionManager.getAccessToken() ?: return
        val refreshToken = sessionManager.getRefreshToken() ?: return
        try {
            supabase.auth.importSession(
                UserSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    tokenType = "Bearer",
                    expiresIn = 3600,
                    user = null
                )
            )
            supabase.auth.refreshCurrentSession()
            Log.d(TAG, "Session restored before upload")
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore session: ${e.message}")
        }
    }

    suspend fun uploadFile(imageBytes: ByteArray): String {
        ensureSession()
        val userId = getUserId()
        val path = "$userId/avatar.jpg"
        val bucket = supabase.storage.from("avatars")
        bucket.upload(path, imageBytes, upsert = true)
        return bucket.publicUrl(path)
    }

    suspend fun deleteFile() {
        val path = "${getUserId()}/avatar.jpg"
        supabase.storage.from("avatars").delete(path)
    }
}
