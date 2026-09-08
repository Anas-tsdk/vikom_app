package com.example.tv_caller_app.data.datasource

import com.example.tv_caller_app.auth.SessionManager

class LocalSessionDataSource private constructor(
    private val sessionManager: SessionManager
) {

    companion object {
        @Volatile private var instance: LocalSessionDataSource? = null
        fun getInstance(sessionManager: SessionManager): LocalSessionDataSource =
            instance ?: synchronized(this) { instance ?: LocalSessionDataSource(sessionManager).also { instance = it } }
    }

    fun saveSession(
        userId: String,
        accessToken: String,
        refreshToken: String,
        email: String,
        emailConfirmed: Boolean
    ) = sessionManager.saveSession(userId, accessToken, refreshToken, email, emailConfirmed)

    fun clearSession() = sessionManager.clearSession()

    fun isLoggedIn(): Boolean = sessionManager.isLoggedIn()

    fun getUserId(): String? = sessionManager.getUserId()

    fun getAccessToken(): String? = sessionManager.getAccessToken()

    fun getRefreshToken(): String? = sessionManager.getRefreshToken()

    fun getEmail(): String? = sessionManager.getEmail()

    fun getUsername(): String? = sessionManager.getUsername()

    fun updateEmailConfirmed(confirmed: Boolean) = sessionManager.updateEmailConfirmed(confirmed)

    fun getSessionInfo(): Map<String, String> = sessionManager.getSessionInfo()
}
