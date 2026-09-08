package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.data.datasource.PriorityDataSource

class PriorityRepository private constructor(private val dataSource: PriorityDataSource) {

    companion object {
        private const val TAG = "PriorityRepository"
        const val MAX_SLOTS = 4

        @Volatile private var instance: PriorityRepository? = null
        fun getInstance(dataSource: PriorityDataSource): PriorityRepository =
            instance ?: synchronized(this) {
                instance ?: PriorityRepository(dataSource).also { instance = it }
            }
    }

    @Volatile private var cache: Map<Int, String> = emptyMap()

    suspend fun refresh() {
        cache = try {
            dataSource.getAllSlots()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load priority slots", e)
            cache
        }
    }

    fun getSlot(contactId: String): Int =
        cache.entries.firstOrNull { it.value == contactId }?.key ?: 0

    fun isFull(): Boolean = cache.size >= MAX_SLOTS

    suspend fun assign(contactId: String): Int {
        val existing = getSlot(contactId)
        if (existing > 0) return existing
        val slot = (1..MAX_SLOTS).firstOrNull { it !in cache.keys } ?: return 0
        try {
            dataSource.upsertSlot(slot, contactId)
            cache = cache + (slot to contactId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to assign priority slot", e)
        }
        return slot
    }

    suspend fun remove(contactId: String) {
        val slot = getSlot(contactId)
        if (slot == 0) return
        try {
            dataSource.deleteByContactId(contactId)
            cache = cache - slot
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove priority slot", e)
        }
    }

    suspend fun removeOrphans(validContactIds: Set<String>) {
        val orphans = cache.entries.filter { it.value !in validContactIds }
        for ((slot, contactId) in orphans) {
            try {
                dataSource.deleteByContactId(contactId)
                cache = cache - slot
                Log.d(TAG, "Removed orphaned priority slot $slot for $contactId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove orphaned slot $slot", e)
            }
        }
    }
}
