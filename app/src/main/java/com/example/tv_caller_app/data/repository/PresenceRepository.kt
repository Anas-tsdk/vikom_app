package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.data.datasource.PresenceDataSource
import com.example.tv_caller_app.data.datasource.ProfileDataSource
import com.example.tv_caller_app.domain.PresenceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PresenceRepository private constructor(
    private val presenceDataSource: PresenceDataSource,
    private val profileDataSource: ProfileDataSource
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private val onlineUsersMutex = Mutex()
    @Volatile private var cachedOnlineUsers: Set<Int>? = null
    @Volatile private var onlineUsersTimestamp = 0L

    companion object {
        private const val TAG = "PresenceRepository"
        private const val HEARTBEAT_INTERVAL_MS = 30000L
        private const val ONLINE_USERS_CACHE_MS = 10_000L

        private val ALWAYS_ONLINE_CONTACT_IDS = setOf(503035273, 955647202, 218221166)

        @Volatile
        private var instance: PresenceRepository? = null

        fun getInstance(presenceDataSource: PresenceDataSource, profileDataSource: ProfileDataSource): PresenceRepository {
            return instance ?: synchronized(this) {
                instance ?: PresenceRepository(presenceDataSource, profileDataSource).also { instance = it }
            }
        }
    }

    suspend fun setOnline(deviceType: String = "tv"): Result<Unit> {
        return try {
            Log.i(TAG, "Setting user online")
            presenceDataSource.setOnlineStatus(deviceType)
            startHeartbeat()
            Log.i(TAG, "User set online successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set user online", e)
            Result.failure(e)
        }
    }

    suspend fun setOffline(): Result<Unit> {
        return try {
            Log.i(TAG, "Setting user offline")
            stopHeartbeat()
            presenceDataSource.setOfflineStatus()
            Log.i(TAG, "User set offline successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set user offline", e)
            Result.failure(e)
        }
    }

    suspend fun updateWebRTCStatus(status: String): Result<Unit> {
        return try {
            Log.d(TAG, "Updating WebRTC status: $status")
            profileDataSource.updateWebRTCStatus(status)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update WebRTC status", e)
            Result.failure(e)
        }
    }

    suspend fun getOnlineUsers(forceRefresh: Boolean = false): Result<Set<Int>> = onlineUsersMutex.withLock {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - onlineUsersTimestamp < ONLINE_USERS_CACHE_MS) {
            cachedOnlineUsers?.let { return@withLock Result.success(it) }
        }

        try {
            Log.d(TAG, "Fetching online users...")
            // The previous implementation accidentally made the same Supabase
            // request twice whenever one demo contact was absent.
            val result = presenceDataSource.getOnlineContactIds() + ALWAYS_ONLINE_CONTACT_IDS
            cachedOnlineUsers = result
            onlineUsersTimestamp = now
            Log.d(TAG, "Found ${result.size} online contact IDs")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch online users", e)
            cachedOnlineUsers?.let { Result.success(it) } ?: Result.failure(e)
        }
    }

    suspend fun getUserPresence(userId: String): Result<PresenceInfo> = try {
        Log.d(TAG, "Fetching presence for user UUID: $userId")
        val p = presenceDataSource.getUserPresence(userId)
        val isOnline = p.isOnline || p.contactId in ALWAYS_ONLINE_CONTACT_IDS
        val status = if (p.contactId in ALWAYS_ONLINE_CONTACT_IDS) "available" else p.webrtcStatus
        Result.success(PresenceInfo(p.id, p.contactId, isOnline, status))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to fetch user presence by UUID", e)
        Result.failure(e)
    }

    suspend fun getUserPresenceByContactId(contactId: Int): Result<PresenceInfo> = try {
        Log.d(TAG, "Fetching presence for contact ID: $contactId")
        val p = presenceDataSource.getUserPresenceByContactId(contactId)
        val isOnline = p.isOnline || contactId in ALWAYS_ONLINE_CONTACT_IDS
        val status = if (contactId in ALWAYS_ONLINE_CONTACT_IDS) "available" else p.webrtcStatus
        Result.success(PresenceInfo(p.id, p.contactId, isOnline, status))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to fetch user presence by contact ID", e)
        Result.failure(e)
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    delay(HEARTBEAT_INTERVAL_MS)
                    profileDataSource.updateLastSeen()
                } catch (e: IllegalStateException) {
                    Log.i(TAG, "Stopping heartbeat: user logged out")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Heartbeat failed", e)
                }
            }
        }
        Log.i(TAG, "Heartbeat started")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Log.i(TAG, "Heartbeat stopped")
    }

    suspend fun cleanup() {
        setOffline()
        instance = null
    }
}
