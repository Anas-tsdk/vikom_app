package com.example.tv_caller_app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.domain.ContactInfo
import com.example.tv_caller_app.domain.UserSearchResult

class ContactsViewModel(application: Application) : AndroidViewModel(application) {

    private val contactRepository = getApplication<TVCallerApplication>().getContactRepository()
    private val presenceRepository = getApplication<TVCallerApplication>().getPresenceRepository()

    companion object {
        private const val TAG = "ContactsViewModel"
    }

    private var allContactsList: List<ContactInfo> = emptyList()

    private var currentQuery: String = ""
    private var searchJob: Job? = null

    private val _displayedContacts = MutableLiveData<List<ContactInfo>>()
    val displayedContacts: LiveData<List<ContactInfo>> = _displayedContacts

    private val _searchResults = MutableLiveData<List<UserSearchResult>>()
    val searchResults: LiveData<List<UserSearchResult>> = _searchResults

    private val _isSearchingGlobal = MutableLiveData<Boolean>()

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _contactAdded = MutableLiveData<Boolean>()
    val contactAdded: LiveData<Boolean> = _contactAdded

    fun loadAllContacts() {
        Log.d(TAG, "loadAllContacts() called")
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val contacts = contactRepository.getAllContacts()
                val onlineIds = presenceRepository.getOnlineUsers().getOrDefault(emptySet())
                allContactsList = contacts.map { it.copy(isOnline = it.contactId in onlineIds) }
                Log.d(TAG, "Loaded ${allContactsList.size} contacts")
                applyFilter()

                if (allContactsList.isEmpty()) {
                    _errorMessage.value = "No contacts found"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading contacts", e)
                _errorMessage.value = "Failed to load contacts: ${e.message}"
                _displayedContacts.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refreshContacts() {
        Log.d(TAG, "refreshContacts() called")
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val contacts = contactRepository.getAllContacts(forceRefresh = true)
                val onlineIds = presenceRepository.getOnlineUsers().getOrDefault(emptySet())
                allContactsList = contacts.map { it.copy(isOnline = it.contactId in onlineIds) }
                Log.d(TAG, "Refreshed ${allContactsList.size} contacts")
                applyFilter()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing contacts", e)
                _errorMessage.value = "Failed to refresh contacts: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun filterContacts(query: String) {
        currentQuery = query
        applyFilter()

        searchJob?.cancel()
        if (query.isNotBlank()) {
            searchJob = viewModelScope.launch {
                delay(300)
                searchAllUsers(query)
            }
        } else {
            _searchResults.value = emptyList()
            _isSearchingGlobal.value = false
        }
    }

    private suspend fun searchAllUsers(query: String) {
        _isSearchingGlobal.value = true
        try {
            val results = contactRepository.searchAllUsers(query)
            val contactIds = allContactsList.map { it.contactId }.toSet()
            val nonContacts = results.filter { it.contactId !in contactIds }
            _searchResults.value = nonContacts
            Log.d(TAG, "Global search: ${nonContacts.size} non-contact results")
        } catch (e: Exception) {
            Log.e(TAG, "Error searching all users", e)
            _searchResults.value = emptyList()
        } finally {
            _isSearchingGlobal.value = false
        }
    }

    private fun applyFilter() {
        val filtered = if (currentQuery.isBlank()) {
            allContactsList
        } else {
            allContactsList.filter {
                it.username.contains(currentQuery, ignoreCase = true) ||
                    it.contactId.toString().contains(currentQuery)
            }
        }
        _displayedContacts.value = filtered.sortedBy { it.username.lowercase() }
    }

    fun resetContactAdded() {
        _contactAdded.value = false
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun isContactInList(contactId: Int): Boolean {
        return allContactsList.any { it.contactId == contactId }
    }
}
