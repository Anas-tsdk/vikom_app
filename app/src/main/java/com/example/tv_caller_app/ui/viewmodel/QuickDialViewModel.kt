package com.example.tv_caller_app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.data.repository.PriorityRepository
import com.example.tv_caller_app.domain.ContactInfo
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class QuickDialViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<TVCallerApplication>()
    private val quickDialRepository = app.getQuickDialRepository()
    private val presenceRepository = app.getPresenceRepository()
    private val contactRepository = app.getContactRepository()
    private val priorityRepository = app.getPriorityRepository()

    companion object {
        private const val TAG = "QuickDialViewModel"
    }

    private var allContactsList: List<ContactInfo> = emptyList()
    private var refreshJob: Job? = null

    private val _allContacts = MutableLiveData<List<ContactInfo>>(emptyList())
    val allContacts: LiveData<List<ContactInfo>> = _allContacts

    private val _allFavorites = MutableLiveData<List<ContactInfo>>(emptyList())
    val allFavorites: LiveData<List<ContactInfo>> = _allFavorites

    private val _onlineDisplayContacts = MutableLiveData<List<ContactInfo>>(emptyList())
    val onlineDisplayContacts: LiveData<List<ContactInfo>> = _onlineDisplayContacts

    private val _onlineCount = MutableLiveData(0)
    val onlineCount: LiveData<Int> = _onlineCount

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    fun refreshQuickDialContacts(forceRefresh: Boolean = true) {
        Log.d(TAG, "refreshQuickDialContacts() called")
        // onResume and the app-foreground observer often fire together. One
        // in-flight refresh is enough and avoids doubling all Supabase calls.
        if (refreshJob?.isActive == true) return

        val hasVisibleData = _onlineDisplayContacts.value?.isNotEmpty() == true ||
            _allFavorites.value?.isNotEmpty() == true
        _isLoading.value = !hasVisibleData
        _errorMessage.value = null

        refreshJob = viewModelScope.launch {
            try {
                // These sources are independent, so load them together. Presence
                // is cached briefly and reused by the quick-dial ranking below.
                val (raw, onlineIds) = coroutineScope {
                    val priorityRequest = async { priorityRepository.refresh() }
                    val contactsRequest = async { contactRepository.getAllContacts(forceRefresh) }
                    val presenceRequest = async { presenceRepository.getOnlineUsers() }
                    priorityRequest.await()
                    contactsRequest.await() to presenceRequest.await().getOrDefault(emptySet())
                }
                priorityRepository.removeOrphans(raw.map { it.id }.toSet())

                allContactsList = raw.map { it.copy(isOnline = it.contactId in onlineIds, prioritySlot = priorityRepository.getSlot(it.id)) }
                _allContacts.value = allContactsList
                _allFavorites.value = sortedFavorites(allContactsList)
                Log.d(TAG, "All favorites: ${_allFavorites.value?.size}")

                val entries = quickDialRepository.getQuickDialEntries(forceRefresh = forceRefresh)
                _onlineDisplayContacts.value = entries.map { it.contact }
                Log.d(TAG, "Quick dial entries: ${entries.size}")

                _onlineCount.value = onlineIds.intersect(raw.map { it.contactId }.toSet()).size
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing contacts", e)
                _errorMessage.value = "Failed to refresh contacts: ${e.message}"
            } finally {
                _isLoading.value = false
                refreshJob = null
            }
        }
    }

    fun loadOnlineCount() {
        viewModelScope.launch {
            try {
                priorityRepository.refresh()
                val raw = contactRepository.getAllContacts()
                priorityRepository.removeOrphans(raw.map { it.id }.toSet())
                val onlineResult = presenceRepository.getOnlineUsers()
                val onlineIds = onlineResult.getOrDefault(emptySet())

                allContactsList = raw.map { it.copy(isOnline = it.contactId in onlineIds, prioritySlot = priorityRepository.getSlot(it.id)) }
                _allContacts.value = allContactsList
                _allFavorites.value = sortedFavorites(allContactsList)
                _onlineCount.value = onlineIds.intersect(raw.map { it.contactId }.toSet()).size

                val entries = quickDialRepository.getQuickDialEntries(forceRefresh = true)
                _onlineDisplayContacts.value = entries.map { it.contact }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading online count", e)
                _onlineCount.value = 0
            }
        }
    }

    fun toggleFavorite(contactId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            val success = contactRepository.updateFavoriteStatus(contactId, isFavorite)
            if (success) {
                if (!isFavorite) priorityRepository.remove(contactId)
                allContactsList = allContactsList.map {
                    if (it.id == contactId) it.copy(isFavorite = isFavorite, prioritySlot = if (isFavorite) it.prioritySlot else 0) else it
                }
                _allContacts.postValue(allContactsList)
                _allFavorites.postValue(sortedFavorites(allContactsList))
            } else {
                _errorMessage.postValue("Kunne ikke oppdatere favoritt")
            }
        }
    }

    fun togglePriority(contactId: String) {
        viewModelScope.launch {
            val current = priorityRepository.getSlot(contactId)
            if (current > 0) {
                priorityRepository.remove(contactId)
            } else {
                priorityRepository.assign(contactId)
            }
            allContactsList = allContactsList.map {
                it.copy(prioritySlot = priorityRepository.getSlot(it.id))
            }
            _allContacts.postValue(allContactsList)
            _allFavorites.postValue(sortedFavorites(allContactsList))
        }
    }

    fun isPriorityFull(): Boolean =
        allContactsList.count { it.prioritySlot > 0 } >= PriorityRepository.MAX_SLOTS

    private fun sortedFavorites(list: List<ContactInfo>): List<ContactInfo> =
        list.filter { it.isFavorite }.sortedWith(
            compareBy<ContactInfo> { if (it.prioritySlot > 0) it.prioritySlot else Int.MAX_VALUE }
                .thenBy { it.username }
        )

    fun clearError() {
        _errorMessage.value = null
    }
}
