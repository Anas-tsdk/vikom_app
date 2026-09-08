package com.example.tv_caller_app.data.datasource

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.dto.PrioritySlotDto
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from

class PriorityDataSource private constructor(private val sessionManager: SessionManager) {

    companion object {
        private const val TAG = "PriorityDataSource"
        private const val TABLE = "priority_contacts"

        @Volatile private var instance: PriorityDataSource? = null
        fun getInstance(sessionManager: SessionManager): PriorityDataSource =
            instance ?: synchronized(this) {
                instance ?: PriorityDataSource(sessionManager).also { instance = it }
            }
    }

    private val supabase = SupabaseClient.client
    private fun getUserId() = sessionManager.getUserId() ?: error("No user id")

    suspend fun getAllSlots(): Map<Int, String> {
        val userId = getUserId()
        return supabase.from(TABLE)
            .select { filter { eq("user_id", userId) } }
            .decodeList<PrioritySlotDto>()
            .associate { it.slot to it.contactId }
    }

    suspend fun upsertSlot(slot: Int, contactId: String) {
        val dto = PrioritySlotDto(userId = getUserId(), slot = slot, contactId = contactId)
        supabase.from(TABLE).upsert(dto)
        Log.d(TAG, "Upserted slot $slot → $contactId")
    }

    suspend fun deleteByContactId(contactId: String) {
        val userId = getUserId()
        supabase.from(TABLE).delete {
            filter {
                eq("user_id", userId)
                eq("contact_id", contactId)
            }
        }
        Log.d(TAG, "Deleted priority for contact $contactId")
    }
}
