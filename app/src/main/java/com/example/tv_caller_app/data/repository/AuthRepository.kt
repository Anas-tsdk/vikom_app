package com.example.tv_caller_app.data.repository

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.example.tv_caller_app.data.datasource.AuthDataSource
import com.example.tv_caller_app.data.datasource.LocalSessionDataSource
import com.example.tv_caller_app.data.datasource.ProfileDataSource

class AuthRepository private constructor(
    private val authDataSource: AuthDataSource,
    private val sessionDataSource: LocalSessionDataSource,
    private val profileDataSource: ProfileDataSource
) {

    companion object {
        private const val TAG = "AuthRepository"

        @Volatile
        private var instance: AuthRepository? = null

        fun getInstance(
            authDataSource: AuthDataSource,
            sessionDataSource: LocalSessionDataSource,
            profileDataSource: ProfileDataSource
        ): AuthRepository {
            return instance ?: synchronized(this) {
                instance ?: AuthRepository(authDataSource, sessionDataSource, profileDataSource).also { instance = it }
            }
        }
    }

    suspend fun signUp(
        email: String,
        password: String,
        username: String? = null,
        phoneNumber: String? = null
    ): Result<String> {
        return retryOnNetworkError {
            try {
                Log.d(TAG, "Attempting sign up for email: $email, username: $username")

                val metadata = if (username != null || phoneNumber != null) {
                    buildJsonObject {
                        if (username != null) put("username", username)
                        if (phoneNumber != null) put("phone_number", phoneNumber)
                    }
                } else null

                authDataSource.signUp(email, password, metadata)

                Log.d(TAG, "Sign up successful, verification email sent to: $email")
                Result.success("Verification email sent to $email")
            } catch (e: Exception) {
                Log.e(TAG, "Sign up failed: ${e.message}", e)
                Result.failure(mapAuthException(e))
            }
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> {
        return retryOnNetworkError {
            try {
                Log.d(TAG, "Attempting sign in for email: $email")

                authDataSource.signIn(email, password)

                if (!authDataSource.isEmailConfirmed()) {
                    authDataSource.signOut()
                    throw Exception("Please verify your email before signing in. Check your inbox for the verification link.")
                }

                val userId = authDataSource.getCurrentUserId()
                    ?: throw Exception("Authentication failed - no user ID")
                val accessToken = authDataSource.getAccessToken()
                    ?: throw Exception("Authentication failed - no access token")
                val refreshToken = authDataSource.getRefreshToken()
                    ?: throw Exception("Authentication failed - no refresh token")
                val userEmail = authDataSource.getCurrentUserEmail() ?: email

                try {
                    ensureProfileExists(userId, userEmail)
                } catch (profileError: Exception) {
                    Log.w(TAG, "Profile creation/check failed: ${profileError.message}")
                }

                sessionDataSource.saveSession(
                    userId = userId,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    email = userEmail,
                    emailConfirmed = true
                )
                Log.d(TAG, "Sign in successful for user: $userId")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Sign in failed: ${e.message}", e)
                Result.failure(mapAuthException(e))
            }
        }
    }

    suspend fun signOut(): Result<Unit> {
        return try {
            val userId = sessionDataSource.getUserId()
            Log.d(TAG, "Signing out user: $userId")
            authDataSource.signOut()
            sessionDataSource.clearSession()
            Log.d(TAG, "Sign out successful")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed: ${e.message}", e)
            sessionDataSource.clearSession()
            Result.failure(e)
        }
    }

    suspend fun restoreSession() {
        if (!authDataSource.hasActiveSupabaseSession()) {
            val accessToken = sessionDataSource.getAccessToken() ?: return
            val refreshToken = sessionDataSource.getRefreshToken() ?: return
            authDataSource.importSession(accessToken, refreshToken)
            Log.d(TAG, "Session restored from local storage (no network)")
        }
    }

    suspend fun refreshSession(): Result<Unit> {
        return try {
            Log.d(TAG, "Refreshing session...")

            if (!authDataSource.hasActiveSupabaseSession()) {
                val accessToken = sessionDataSource.getAccessToken()
                val refreshToken = sessionDataSource.getRefreshToken()
                if (accessToken != null && refreshToken != null) {
                    Log.d(TAG, "No in-memory session — importing tokens from stored session")
                    authDataSource.importSession(accessToken, refreshToken)
                } else {
                    Log.w(TAG, "No stored tokens — cannot restore session")
                }
            }

            authDataSource.refreshSession()

            val userId = authDataSource.getCurrentUserId()
                ?: throw Exception("No user ID found after refresh")
            val accessToken = authDataSource.getAccessToken()
                ?: throw Exception("No access token found after refresh")
            val refreshToken = authDataSource.getRefreshToken()
                ?: throw Exception("No refresh token found after refresh")
            val userEmail = authDataSource.getCurrentUserEmail() ?: sessionDataSource.getEmail() ?: ""
            val emailConfirmed = authDataSource.isEmailConfirmed()

            sessionDataSource.saveSession(
                userId = userId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                email = userEmail,
                emailConfirmed = emailConfirmed
            )

            Log.d(TAG, "Session refreshed successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Session refresh failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun resendVerificationEmail(email: String): Result<Unit> {
        return retryOnNetworkError {
            try {
                Log.d(TAG, "Resending verification email to: $email")
                authDataSource.resendVerificationEmail(email)
                Log.d(TAG, "Verification email resent successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resend verification email: ${e.message}", e)
                Result.failure(mapAuthException(e))
            }
        }
    }

    suspend fun resetPassword(email: String): Result<Unit> {
        return retryOnNetworkError {
            try {
                Log.d(TAG, "Requesting password reset for: $email")
                authDataSource.resetPassword(email)
                Log.d(TAG, "Password reset email sent successfully")
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Password reset failed: ${e.message}", e)
                Result.failure(mapAuthException(e))
            }
        }
    }

    suspend fun verifyRecoveryOtp(email: String, token: String): Result<Unit> {
        return try {
            Log.d(TAG, "Verifying recovery OTP for: $email")
            authDataSource.verifyRecoveryOtp(email, token)
            Log.d(TAG, "Recovery OTP verified successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Recovery OTP verification failed: ${e.message}", e)
            Result.failure(mapAuthException(e))
        }
    }

    suspend fun updatePassword(newPassword: String): Result<Unit> {
        return try {
            Log.d(TAG, "Updating password")
            authDataSource.updatePassword(newPassword)
            signOut()
            Log.d(TAG, "Password updated and session cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Password update failed: ${e.message}", e)
            Result.failure(mapAuthException(e))
        }
    }

    suspend fun isSessionValid(): Boolean {
        return try {
            if (!sessionDataSource.isLoggedIn()) {
                Log.d(TAG, "No stored session found")
                return false
            }
            sessionDataSource.updateEmailConfirmed(true)
            Log.d(TAG, "Valid stored session found")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking session validity: ${e.message}", e)
            false
        }
    }

    fun getUsername(): String? = sessionDataSource.getUsername()
    fun getSessionInfo(): Map<String, String> = sessionDataSource.getSessionInfo()

    private suspend fun ensureProfileExists(userId: String, email: String) {
        try {
            if (!profileDataSource.profileExists(userId)) {
                Log.d(TAG, "Profile not found for user $userId, creating...")
                val username = authDataSource.getCurrentUserUsername()
                if (username == null) Log.w(TAG, "username is null — using email prefix as fallback")
                val finalUsername = username ?: email.substringBefore("@")
                profileDataSource.insertProfile(userId, finalUsername, email)
                Log.d(TAG, "Profile created successfully for user: $userId")
            } else {
                Log.d(TAG, "Profile already exists for user $userId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring profile exists: ${e.message}", e)
            throw e
        }
    }

    private fun mapAuthException(e: Exception): Exception {
        val message = when {
            e.message?.contains("Invalid login credentials", ignoreCase = true) == true -> "Invalid email or password"
            e.message?.contains("Email not confirmed", ignoreCase = true) == true -> "Please verify your email before signing in. Check your inbox."
            e.message?.contains("User already registered", ignoreCase = true) == true -> "An account with this email already exists. Try logging in instead."
            e.message?.contains("User not found", ignoreCase = true) == true -> "No account found with this email"
            e.message?.contains("Invalid email", ignoreCase = true) == true -> "Please enter a valid email address"
            e.message?.contains("Email link is invalid", ignoreCase = true) == true -> "Verification link is invalid or expired. Request a new one."
            e.message?.contains("OTP expired", ignoreCase = true) == true -> "Verification code expired. Request a new one."
            e.message?.contains("Password", ignoreCase = true) == true && e.message?.contains("weak", ignoreCase = true) == true -> "Password is too weak. Use at least 8 characters with uppercase, lowercase, number, and special character."
            e.message?.contains("Password should be at least", ignoreCase = true) == true -> "Password must be at least 8 characters long"
            e.message?.contains("network", ignoreCase = true) == true || e.message?.contains("timeout", ignoreCase = true) == true -> "Network error. Please check your connection and try again."
            e.message?.contains("Unable to resolve host", ignoreCase = true) == true -> "Cannot reach server. Check your internet connection."
            e.message?.contains("refresh_token_not_found", ignoreCase = true) == true -> "Session expired. Please log in again."
            e.message?.contains("invalid_grant", ignoreCase = true) == true -> "Session expired. Please log in again."
            e.message?.contains("JWT", ignoreCase = true) == true || e.message?.contains("token", ignoreCase = true) == true -> "Session expired. Please log in again."
            e.message?.contains("rate limit", ignoreCase = true) == true || e.message?.contains("too many requests", ignoreCase = true) == true -> "Too many attempts. Please wait a moment and try again."
            e.message?.contains("already been taken", ignoreCase = true) == true -> "This email is already registered. Try logging in instead."
            else -> { Log.w(TAG, "Unmapped auth error: ${e.message}"); "Authentication error. Please try again." }
        }
        return Exception(message)
    }

    private suspend fun <T> retryOnNetworkError(
        maxRetries: Int = 3,
        initialDelay: Long = 1000L,
        maxDelay: Long = 5000L,
        factor: Double = 2.0,
        block: suspend () -> Result<T>
    ): Result<T> {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            val result = block()
            if (result.isSuccess) return result
            val error = result.exceptionOrNull()
            val isNetworkError = error?.message?.contains("network", ignoreCase = true) == true ||
                    error?.message?.contains("timeout", ignoreCase = true) == true ||
                    error?.message?.contains("Unable to resolve host", ignoreCase = true) == true
            if (isNetworkError) {
                if (attempt < maxRetries - 1) {
                    Log.d(TAG, "Network error on attempt ${attempt + 1}, retrying after ${currentDelay}ms")
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
                } else {
                    Log.w(TAG, "Network error after $maxRetries attempts, giving up")
                }
            } else {
                Log.d(TAG, "Non-network error, not retrying: ${error?.message}")
                return result
            }
        }
        return Result.failure(Exception("Network error after $maxRetries attempts. Please check your connection."))
    }
}
