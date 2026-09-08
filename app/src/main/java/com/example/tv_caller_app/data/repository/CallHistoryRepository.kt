package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.data.datasource.CallHistoryDataSource

class CallHistoryRepository private constructor(
    private val dataSource: CallHistoryDataSource
) {

    companion object {
        private const val TAG = "CallHistoryRepository"

        @Volatile
        private var instance: CallHistoryRepository? = null

        fun getInstance(dataSource: CallHistoryDataSource): CallHistoryRepository {
            return instance ?: synchronized(this) {
                instance ?: CallHistoryRepository(dataSource).also { instance = it }
            }
        }
    }

    fun invalidateCache() {
        dataSource.invalidateCache()
    }

    suspend fun getAllCallHistory(forceRefresh: Boolean = false) = try {
        Log.d(TAG, "Getting all call history...")
        dataSource.getAllCallHistory(forceRefresh)
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching call history: ${e.message}", e)
        emptyList()
    }

    suspend fun getRecentCallHistory(limit: Int = 10, forceRefresh: Boolean = false) = try {
        Log.d(TAG, "Getting recent $limit call history records...")
        getAllCallHistory(forceRefresh).take(limit)
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching recent call history: ${e.message}", e)
        emptyList()
    }

    suspend fun getCallHistoryByType(callType: String, forceRefresh: Boolean = false) = try {
        Log.d(TAG, "Getting call history of type '$callType'...")
        getAllCallHistory(forceRefresh).filter { it.callType.lowercase() == callType.lowercase() }
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching call history by type: ${e.message}", e)
        emptyList()
    }

    suspend fun getCallHistoryForContact(contactId: Int, forceRefresh: Boolean = false) = try {
        Log.d(TAG, "Getting call history for contact ID $contactId...")
        getAllCallHistory(forceRefresh).filter { it.contactId == contactId }
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching call history for contact: ${e.message}", e)
        emptyList()
    }

    suspend fun logCall(
        contactId: Int?,
        contactName: String?,
        callType: String,
        callDuration: Int,
        endReason: String
    ) {
        try {
            Log.d(TAG, "Logging call: type=$callType, contact=$contactName, duration=${callDuration}s, reason=$endReason")
            dataSource.logCall(
                contactId = contactId,
                contactName = contactName,
                callType = callType,
                callDuration = callDuration,
                endReason = endReason
            )
            invalidateCache()
            Log.i(TAG, "Call logged successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log call: ${e.message}", e)
        }
    }

    suspend fun getCallStatistics(forceRefresh: Boolean = false): Map<String, Int> {
        return try {
            Log.d(TAG, "Calculating call statistics...")
            val allCalls = getAllCallHistory(forceRefresh)
            val stats = mapOf(
                "total" to allCalls.size,
                "incoming" to allCalls.count { it.callType.lowercase() == "incoming" },
                "outgoing" to allCalls.count { it.callType.lowercase() == "outgoing" },
                "missed" to allCalls.count { it.callType.lowercase() == "missed" }
            )
            Log.d(TAG, "Call statistics: $stats")
            stats
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating call statistics: ${e.message}", e)
            emptyMap()
        }
    }
}
