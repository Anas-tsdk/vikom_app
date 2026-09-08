package com.example.tv_caller_app.data.datasource

import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.gotrue.OtpType
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.user.UserSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class AuthDataSource private constructor(private val sessionManager: SessionManager) {

    companion object {
        @Volatile
        private var instance: AuthDataSource? = null

        fun getInstance(sessionManager: SessionManager): AuthDataSource {
            return instance ?: synchronized(this) {
                instance ?: AuthDataSource(sessionManager).also { instance = it }
            }
        }
    }

    private val supabase = SupabaseClient.client

    suspend fun signUp(email: String, password: String, metadata: JsonObject? = null) {
        supabase.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            if (metadata != null) data = metadata
        }
    }

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    fun hasActiveSupabaseSession(): Boolean = supabase.auth.currentSessionOrNull() != null

    fun isEmailConfirmed(): Boolean = supabase.auth.currentUserOrNull()?.confirmedAt != null

    fun getCurrentUserEmail(): String? = supabase.auth.currentUserOrNull()?.email

    fun getCurrentUserId(): String? = supabase.auth.currentSessionOrNull()?.user?.id

    fun getCurrentUserUsername(): String? = try {
        supabase.auth.currentUserOrNull()
            ?.userMetadata?.get("username")
            ?.jsonPrimitive?.contentOrNull
    } catch (e: Exception) {
        null
    }

    fun getAccessToken(): String? = supabase.auth.currentSessionOrNull()?.accessToken

    fun getRefreshToken(): String? = supabase.auth.currentSessionOrNull()?.refreshToken

    suspend fun importSession(accessToken: String, refreshToken: String) {
        supabase.auth.importSession(
            UserSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                tokenType = "Bearer",
                expiresIn = 3600,
                user = null
            )
        )
    }

    suspend fun refreshSession() {
        supabase.auth.refreshCurrentSession()
    }

    suspend fun resendVerificationEmail(email: String) {
        supabase.auth.resendEmail(type = OtpType.Email.SIGNUP, email = email)
    }

    suspend fun resetPassword(email: String, redirectUrl: String? = null) {
        supabase.auth.resetPasswordForEmail(email, redirectUrl)
    }

    suspend fun verifyRecoveryOtp(email: String, token: String) {
        supabase.auth.verifyEmailOtp(
            type = OtpType.Email.RECOVERY,
            email = email,
            token = token
        )
    }

    suspend fun updatePassword(newPassword: String) {
        supabase.auth.updateUser {
            password = newPassword
        }
    }
}
