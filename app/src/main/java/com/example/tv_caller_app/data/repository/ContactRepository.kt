package com.example.tv_caller_app.data.repository

import android.util.Log
import com.example.tv_caller_app.data.datasource.ContactDataSource
import com.example.tv_caller_app.data.datasource.ProfileDataSource
import com.example.tv_caller_app.domain.ContactInfo
import com.example.tv_caller_app.domain.UserSearchResult
import com.example.tv_caller_app.data.dto.Contact

class ContactRepository private constructor(
    private val contactDataSource: ContactDataSource,
    private val profileDataSource: ProfileDataSource
) {

    companion object {
        private const val TAG = "ContactRepository"

        @Volatile
        private var instance: ContactRepository? = null

        fun getInstance(contactDataSource: ContactDataSource, profileDataSource: ProfileDataSource): ContactRepository {
            return instance ?: synchronized(this) {
                instance ?: ContactRepository(contactDataSource, profileDataSource).also { instance = it }
            }
        }
    }

    fun invalidateCache() {
        contactDataSource.invalidateCache()
    }

    suspend fun getAllContacts(forceRefresh: Boolean = false): List<ContactInfo> = try {
        Log.d(TAG, "Getting all contacts...")
        val rows = contactDataSource.getAllContacts(forceRefresh)
        val profileMap = profileDataSource.getProfilesForContactIds(rows.map { it.contactId })
        rows.map { row ->
            val profile = profileMap[row.contactId]
            ContactInfo(
                id = row.id,
                username = profile?.username ?: "Unknown",
                nickname = row.nickname,
                contactId = row.contactId,
                email = row.email,
                isFavorite = row.isFavorite,
                avatarUrl = profile?.avatarUrl
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching all contacts: ${e.message}", e)
        emptyList()
    }

    suspend fun getContactById(id: String): ContactInfo? = try {
        Log.d(TAG, "Getting contact by ID: $id")
        val row = contactDataSource.getContactById(id) ?: return null
        val profileMap = profileDataSource.getProfilesForContactIds(listOf(row.contactId))
        val profile = profileMap[row.contactId]
        ContactInfo(
            id = row.id,
            username = profile?.username ?: "Unknown",
            nickname = row.nickname,
            contactId = row.contactId,
            email = row.email,
            isFavorite = row.isFavorite,
            avatarUrl = profile?.avatarUrl
        )
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching contact by ID: ${e.message}", e)
        null
    }

    suspend fun getContactByContactId(contactId: Int): ContactInfo? = try {
        Log.d(TAG, "Looking up contact by contact ID: $contactId")
        getAllContacts().find { it.contactId == contactId }
    } catch (e: Exception) {
        Log.e(TAG, "Error looking up contact by contact ID: ${e.message}", e)
        null
    }

    suspend fun getFavoriteContacts(forceRefresh: Boolean = false): List<ContactInfo> = try {
        Log.d(TAG, "Getting favorite contacts...")
        getAllContacts(forceRefresh).filter { it.isFavorite }
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching favorite contacts: ${e.message}", e)
        emptyList()
    }

    suspend fun createContact(
        contactId: Int,
        email: String? = null,
        address: String? = null,
        notes: String? = null,
        isFavorite: Boolean = false
    ): Contact? {
        return try {
            Log.d(TAG, "Creating new contact with contact ID: $contactId")
            val result = contactDataSource.createContact(contactId, email, address, notes, isFavorite)
            invalidateCache()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error creating contact: ${e.message}", e)
            null
        }
    }

    suspend fun updateContact(
        id: String,
        contactId: Int,
        email: String? = null,
        address: String? = null,
        notes: String? = null,
        isFavorite: Boolean = false
    ): Boolean {
        return try {
            Log.d(TAG, "Updating contact: $id")
            val result = contactDataSource.updateContact(id, contactId, email, address, notes, isFavorite)
            invalidateCache()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error updating contact: ${e.message}", e)
            false
        }
    }

    suspend fun updateFavoriteStatus(id: String, isFavorite: Boolean): Boolean {
        return try {
            Log.d(TAG, "Updating favorite status for $id to $isFavorite")
            val result = contactDataSource.updateFavoriteStatus(id, isFavorite)
            invalidateCache()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error updating favorite status: ${e.message}", e)
            false
        }
    }

    suspend fun updateNickname(id: String, nickname: String?): Boolean {
        return try {
            Log.d(TAG, "Updating nickname for $id to '$nickname'")
            val result = contactDataSource.updateNickname(id, nickname)
            invalidateCache()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error updating nickname: ${e.message}", e)
            false
        }
    }

    suspend fun searchAllUsers(query: String): List<UserSearchResult> = try {
        Log.d(TAG, "Searching all users with query: $query")
        val results = profileDataSource.searchAllUsers(query)
            .map { p -> UserSearchResult(p.username ?: "", p.contactId, p.email, p.avatarUrl) }
        Log.d(TAG, "Found ${results.size} users matching '$query'")
        results
    } catch (e: Exception) {
        Log.e(TAG, "Error searching users: ${e.message}", e)
        emptyList()
    }

    suspend fun deleteContact(id: String): Boolean {
        return try {
            Log.d(TAG, "Deleting contact: $id")
            val result = contactDataSource.deleteContact(id)
            invalidateCache()
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting contact: ${e.message}", e)
            false
        }
    }
}
