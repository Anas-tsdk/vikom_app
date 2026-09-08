package com.example.tv_caller_app.auth

import android.util.Log
import com.example.tv_caller_app.data.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SessionRefreshManager(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) {

    companion object {
        private const val TAG = "SessionRefreshManager"
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    fun startPeriodicRefresh() {
        if (isRunning) {
            Log.d(TAG, "Periodic refresh already running")
            return
        }

        isRunning = true
        Log.d(TAG, "Starting periodic session refresh")

        scope.launch {
            while (isRunning) {
                if (sessionManager.isLoggedIn()) {
                    Log.d(TAG, "Attempting session refresh")
                    authRepository.refreshSession()
                        .onSuccess {
                            Log.d(TAG, "Session refreshed successfully")
                        }
                        .onFailure { error ->
                            Log.w(TAG, "Session refresh failed: ${error.message}")
                            if (isTerminalAuthError(error)) {
                                Log.w(TAG, "Terminal auth error — forcing logout")
                                sessionManager.clearSession()
                                sessionManager.signalSessionExpired()
                                isRunning = false
                            }
                        }
                } else {
                    Log.d(TAG, "User not logged in, skipping refresh")
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun isTerminalAuthError(error: Throwable): Boolean {
        val msg = error.message ?: return false
        return msg.contains("refresh_token_not_found", ignoreCase = true) ||
                msg.contains("invalid_grant", ignoreCase = true) ||
                msg.contains("Token has expired or is invalid", ignoreCase = true) ||
                msg.contains("Invalid Refresh Token", ignoreCase = true) ||
                msg.contains("Session expired", ignoreCase = true)
    }

    fun stop() {
        Log.d(TAG, "Stopping periodic session refresh")
        isRunning = false
        scope.cancel()
    }
}
