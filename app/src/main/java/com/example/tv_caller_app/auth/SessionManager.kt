package com.example.tv_caller_app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class SessionManager private constructor(context: Context) {

    private val _sessionExpired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    fun signalSessionExpired() {
        _sessionExpired.tryEmit(Unit)
    }

    private val encryptedPrefs: SharedPreferences

    companion object {
        private const val TAG = "SessionManager"
        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
        }

        private const val PREF_NAME = "auth_session_encrypted"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EMAIL = "email"
        private const val KEY_EMAIL_CONFIRMED = "email_confirmed"
        private const val KEY_SESSION_TIMESTAMP = "session_timestamp"
        private const val KEY_USERNAME = "username"
    }

    init {
        encryptedPrefs = createEncryptedPrefs(context)
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context).also {
                Log.d(TAG, "SessionManager initialized with encrypted storage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences init failed — wiping and retrying", e)
            try {
                context.deleteSharedPreferences(PREF_NAME)
                buildEncryptedPrefs(context).also {
                    Log.d(TAG, "SessionManager re-initialized after wipe (user treated as logged out)")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "EncryptedSharedPreferences init failed even after wipe", e2)
                throw RuntimeException("Failed to initialize secure storage", e2)
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveSession(
        userId: String,
        accessToken: String,
        refreshToken: String,
        email: String,
        emailConfirmed: Boolean
    ) {
        try {
            encryptedPrefs.edit {
                putString(KEY_USER_ID, userId)
                putString(KEY_ACCESS_TOKEN, accessToken)
                putString(KEY_REFRESH_TOKEN, refreshToken)
                putString(KEY_EMAIL, email)
                putBoolean(KEY_EMAIL_CONFIRMED, emailConfirmed)
                putLong(KEY_SESSION_TIMESTAMP, System.currentTimeMillis())
            }
            Log.d(TAG, "Session saved for user: $userId (email: $email)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving session", e)
        }
    }

    fun getUserId(): String? {
        return try {
            encryptedPrefs.getString(KEY_USER_ID, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving user ID", e)
            null
        }
    }

    fun getAccessToken(): String? {
        return try {
            encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving access token", e)
            null
        }
    }

    fun getRefreshToken(): String? {
        return try {
            encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving refresh token", e)
            null
        }
    }

    fun getEmail(): String? {
        return try {
            encryptedPrefs.getString(KEY_EMAIL, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving email", e)
            null
        }
    }

    fun isEmailConfirmed(): Boolean {
        return try {
            encryptedPrefs.getBoolean(KEY_EMAIL_CONFIRMED, false)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking email confirmation", e)
            false
        }
    }

    fun getSessionTimestamp(): Long {
        return try {
            encryptedPrefs.getLong(KEY_SESSION_TIMESTAMP, 0L)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving session timestamp", e)
            0L
        }
    }

    fun isLoggedIn(): Boolean {
        return !getUserId().isNullOrBlank() && !getAccessToken().isNullOrBlank()
    }

    fun updateEmailConfirmed(confirmed: Boolean) {
        try {
            encryptedPrefs.edit {
                putBoolean(KEY_EMAIL_CONFIRMED, confirmed)
            }
            Log.d(TAG, "Email confirmation status updated: $confirmed")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating email confirmation", e)
        }
    }

    fun clearSession() {
        try {
            val userId = getUserId()
            encryptedPrefs.edit { clear() }
            Log.d(TAG, "Session cleared for user: ${userId?.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing session", e)
        }
    }

    fun hasRecentSession(maxAgeHours: Int = 24): Boolean {
        if (!isLoggedIn()) return false

        val timestamp = getSessionTimestamp()
        if (timestamp == 0L) return false

        val ageMillis = System.currentTimeMillis() - timestamp
        val ageHours = ageMillis / (1000 * 60 * 60)
        return ageHours < maxAgeHours
    }

    fun saveUsername(username: String) {
        try {
            encryptedPrefs.edit { putString(KEY_USERNAME, username) }
            Log.d(TAG, "Username saved: $username")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving username", e)
        }
    }

    fun getUsername(): String? {
        return try {
            encryptedPrefs.getString(KEY_USERNAME, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving username", e)
            null
        }
    }

    fun getSessionInfo(): Map<String, String> {
        return mapOf(
            "userId" to (getUserId()?.take(8) ?: "null") + "...",
            "email" to (getEmail() ?: "null"),
            "emailConfirmed" to isEmailConfirmed().toString(),
            "hasAccessToken" to (getAccessToken() != null).toString(),
            "hasRefreshToken" to (getRefreshToken() != null).toString(),
            "sessionAge" to "${(System.currentTimeMillis() - getSessionTimestamp()) / 1000 / 60} minutes"
        )
    }
}
