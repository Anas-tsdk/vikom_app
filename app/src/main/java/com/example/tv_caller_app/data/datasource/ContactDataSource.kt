package com.example.tv_caller_app.data.datasource

import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.dto.Contact
import com.example.tv_caller_app.data.dto.ContactInsert
import com.example.tv_caller_app.data.dto.ContactUpdate
import com.example.tv_caller_app.data.remote.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ContactDataSource private constructor(
    private val sessionManager: SessionManager
) {

    companion object {
        private const val TAG = "ContactDataSource"
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L

        @Volatile private var instance: ContactDataSource? = null
        fun getInstance(sessionManager: SessionManager): ContactDataSource =
            instance ?: synchronized(this) { instance ?: ContactDataSource(sessionManager).also { instance = it } }
    }

    private val supabase = SupabaseClient.client

    private var cachedContacts: List<Contact>? = null
    private var cacheTimestamp: Long = 0

    private fun isCacheValid() =
        cachedContacts != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_DURATION_MS

    private fun getUserId(): String =
        sessionManager.getUserId() ?: throw IllegalStateException("User not logged in")

    fun invalidateCache() {
        Log.d(TAG, "Cache invalidated")
        cachedContacts = null
        cacheTimestamp = 0
    }

    suspend fun getAllContacts(forceRefresh: Boolean = false): List<Contact> {
        if (!forceRefresh && isCacheValid()) {
            Log.d(TAG, "Returning cached contacts (${cachedContacts!!.size} contacts)")
            return cachedContacts!!
        }
        val result = supabase.from("contacts")
            .select { filter { eq("user_id", getUserId()) } }
            .decodeList<Contact>()
        cachedContacts = result
        cacheTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Fetched and cached ${result.size} contacts")
        return result
    }

    suspend fun getContactById(id: String): Contact? =
        supabase.from("contacts")
            .select { filter { eq("id", id) } }
            .decodeList<Contact>()
            .firstOrNull()

    suspend fun createContact(
        contactId: Int,
        email: String?,
        address: String?,
        notes: String?,
        isFavorite: Boolean
    ): Boolean {
        supabase.from("contacts").insert(
            ContactInsert(
                userId = getUserId(),
                contactId = contactId,
                email = email,
                address = address,
                notes = notes,
                isFavorite = isFavorite
            )
        )
        return true
    }

    suspend fun updateContact(
        id: String,
        contactId: Int,
        email: String?,
        address: String?,
        notes: String?,
        isFavorite: Boolean
    ): Boolean {
        supabase.from("contacts")
            .update(ContactUpdate(contactId = contactId, email = email, address = address, notes = notes, isFavorite = isFavorite)) {
                filter { eq("id", id) }
            }
        return true
    }

    suspend fun updateFavoriteStatus(id: String, isFavorite: Boolean): Boolean {
        val update = buildJsonObject { put("is_favorite", isFavorite) }
        supabase.from("contacts")
            .update(update) { filter { eq("id", id) } }
        return true
    }

    suspend fun updateNickname(id: String, nickname: String?): Boolean {
        val update = buildJsonObject { put("nickname", nickname) }
        supabase.from("contacts")
            .update(update) { filter { eq("id", id) } }
        return true
    }

    suspend fun deleteContact(id: String): Boolean {
        supabase.from("contacts")
            .delete { filter { eq("id", id) } }
        return true
    }
}
