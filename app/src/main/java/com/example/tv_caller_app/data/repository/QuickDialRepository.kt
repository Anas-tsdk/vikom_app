package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.domain.QuickDialEntry
import com.example.tv_caller_app.domain.usecase.GetQuickDialEntriesUseCase

class QuickDialRepository private constructor(
    private val useCase: GetQuickDialEntriesUseCase
) {

    companion object {
        private const val TAG = "QuickDialRepository"

        @Volatile
        private var instance: QuickDialRepository? = null

        fun getInstance(getQuickDialEntries: GetQuickDialEntriesUseCase): QuickDialRepository {
            return instance ?: synchronized(this) {
                instance ?: QuickDialRepository(getQuickDialEntries).also { instance = it }
            }
        }
    }

    fun invalidateCache() {
        useCase.invalidateCache()
    }

    suspend fun getQuickDialEntries(forceRefresh: Boolean = false): List<QuickDialEntry> = try {
        useCase(forceRefresh)
    } catch (e: Exception) {
        Log.e(TAG, "Error getting quick dial entries: ${e.message}", e)
        emptyList()
    }
}
