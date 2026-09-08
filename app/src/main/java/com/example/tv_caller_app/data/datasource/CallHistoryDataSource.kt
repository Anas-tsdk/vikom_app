package com.example.tv_caller_app.data.datasource

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.dto.CallHistory
import com.example.tv_caller_app.data.dto.CallHistoryInsert
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from

class CallHistoryDataSource private constructor(
    private val sessionManager: SessionManager
) {

    companion object {
        private const val TAG = "CallHistoryDataSource"
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L

        @Volatile private var instance: CallHistoryDataSource? = null
        fun getInstance(sessionManager: SessionManager): CallHistoryDataSource =
            instance ?: synchronized(this) { instance ?: CallHistoryDataSource(sessionManager).also { instance = it } }
    }

    private val supabase = SupabaseClient.client

    private var cachedCallHistory: List<CallHistory>? = null
    private var cacheTimestamp: Long = 0

    private fun isCacheValid() =
        cachedCallHistory != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_DURATION_MS

    private fun getUserId(): String =
        sessionManager.getUserId() ?: throw IllegalStateException("User not logged in")

    fun invalidateCache() {
        Log.d(TAG, "Cache invalidated")
        cachedCallHistory = null
        cacheTimestamp = 0
    }

    suspend fun getAllCallHistory(forceRefresh: Boolean = false): List<CallHistory> {
        if (!forceRefresh && isCacheValid()) {
            Log.d(TAG, "Returning cached call history (${cachedCallHistory!!.size} records)")
            return cachedCallHistory!!
        }
        val result = supabase.from("call_history")
            .select { filter { eq("user_id", getUserId()) } }
            .decodeList<CallHistory>()
            .sortedByDescending { it.callTimestamp }
        cachedCallHistory = result
        cacheTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Fetched and cached ${result.size} call records")
        return result
    }

    suspend fun logCall(
        contactId: Int?,
        contactName: String?,
        callType: String,
        callDuration: Int,
        endReason: String
    ) {
        supabase.from("call_history").insert(
            CallHistoryInsert(
                userId = getUserId(),
                contactId = contactId,
                contactName = contactName,
                callType = callType,
                callDuration = callDuration,
                endReason = endReason
            )
        )
    }
}
