package com.example.tv_caller_app.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.calling.permissions.PermissionHelper

class ContactDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<TVCallerApplication>()
    private val callHistoryRepository = app.getCallHistoryRepository()
    private val contactRepository = app.getContactRepository()
    private val presenceRepository = app.getPresenceRepository()
    private val priorityRepository = app.getPriorityRepository()

    companion object {
        private const val TAG = "ContactDetailViewModel"
    }

    private val _contactId = MutableLiveData<String>()
    val contactId: LiveData<String> = _contactId

    private val _contactName = MutableLiveData<String>()
    val contactName: LiveData<String> = _contactName

    private val _userContactId = MutableLiveData<Int>()
    val userContactId: LiveData<Int> = _userContactId

    private val _contactEmail = MutableLiveData<String?>()

    private val _callHistoryCount = MutableLiveData<Int>()
    val callHistoryCount: LiveData<Int> = _callHistoryCount

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _isCallInProgress = MutableLiveData(false)
    val isCallInProgress: LiveData<Boolean> = _isCallInProgress

    private val _isDeleted = MutableLiveData(false)
    val isDeleted: LiveData<Boolean> = _isDeleted

    private val _isAdded = MutableLiveData(false)
    val isAdded: LiveData<Boolean> = _isAdded

    private val _isFavorite = MutableLiveData(false)
    val isFavorite: LiveData<Boolean> = _isFavorite

    private val _isPriority = MutableLiveData(false)
    val isPriority: LiveData<Boolean> = _isPriority

    private val _isPriorityFull = MutableLiveData(false)
    val isPriorityFull: LiveData<Boolean> = _isPriorityFull

    private val _nickname = MutableLiveData<String?>()
    val nickname: LiveData<String?> = _nickname

    private val _presenceOnline = MutableLiveData(false)
    val presenceOnline: LiveData<Boolean> = _presenceOnline

    private val _presenceWebrtcStatus = MutableLiveData("offline")
    val presenceWebrtcStatus: LiveData<String> = _presenceWebrtcStatus

    private var avatarUrl: String? = null

    data class CallReadyParams(
        val profileId: String,
        val name: String,
        val contactId: Int,
        val nickname: String?,
        val avatarUrl: String?,
        val withVideo: Boolean
    )

    private val _callReadyEvent = MutableLiveData<CallReadyParams?>()
    val callReadyEvent: LiveData<CallReadyParams?> = _callReadyEvent

    fun consumeCallReadyEvent() {
        _callReadyEvent.value = null
    }

    private val _hasCameraHardware = MutableLiveData(PermissionHelper.hasCameraHardware(application))
    val hasCameraHardware: LiveData<Boolean> = _hasCameraHardware

    private val _requestPermissionsEvent = MutableLiveData<Array<String>?>()
    val requestPermissionsEvent: LiveData<Array<String>?> = _requestPermissionsEvent

    private var _pendingVideoCall = false

    fun onCallRequested(withVideo: Boolean) {
        _pendingVideoCall = withVideo
        val ctx = getApplication<Application>()

        val required = mutableListOf<String>()
        required.add(Manifest.permission.RECORD_AUDIO)
        if (withVideo && PermissionHelper.hasCameraHardware(ctx)) {
            required.add(Manifest.permission.CAMERA)
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            callContact(withVideo)
        } else {
            _requestPermissionsEvent.value = missing.toTypedArray()
        }
    }

    fun onPermissionsResult(allGranted: Boolean, deniedPermissions: List<String> = emptyList()) {
        _requestPermissionsEvent.value = null
        if (allGranted) {
            callContact(_pendingVideoCall)
        } else {
            val names = deniedPermissions.joinToString(", ") { permissionDisplayName(it) }
            _errorMessage.value = "Please grant the following permissions to make calls: $names"
        }
    }

    fun consumePermissionsEvent() {
        _requestPermissionsEvent.value = null
    }

    private fun permissionDisplayName(permission: String) = when (permission) {
        Manifest.permission.RECORD_AUDIO -> "Microphone"
        Manifest.permission.CAMERA -> "Camera"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth"
        else -> permission
    }

    fun checkOnlineStatus(contactId: Int) {
        if (contactId == 0) {
            _presenceOnline.value = false
            _presenceWebrtcStatus.value = "offline"
            return
        }
        viewModelScope.launch {
            try {
                val result = presenceRepository.getUserPresenceByContactId(contactId)
                if (result.isSuccess) {
                    val presence = result.getOrNull()
                    _presenceOnline.value = presence?.isOnline == true
                    _presenceWebrtcStatus.value = presence?.webrtcStatus ?: "offline"
                } else {
                    Log.w(TAG, "Failed to fetch online status: ${result.exceptionOrNull()?.message}")
                    _presenceOnline.value = false
                    _presenceWebrtcStatus.value = "offline"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking online status", e)
                _presenceOnline.value = false
                _presenceWebrtcStatus.value = "offline"
            }
        }
    }

    fun setContactDetails(id: String, name: String, contactId: Int, email: String? = null, isFavorite: Boolean = false, nickname: String? = null, avatarUrl: String? = null) {
        _contactId.value = id
        _contactName.value = name
        _userContactId.value = contactId
        _contactEmail.value = email
        _isFavorite.value = isFavorite
        _nickname.value = nickname
        this.avatarUrl = avatarUrl
        loadCallHistory(contactId)
        viewModelScope.launch {
            priorityRepository.refresh()
            _isPriority.value = priorityRepository.getSlot(id) > 0
            _isPriorityFull.value = priorityRepository.isFull()
        }
    }

    private fun loadCallHistory(contactId: Int) {
        Log.d(TAG, "loadCallHistory() for contact ID $contactId")
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                Log.d(TAG, "Fetching call history...")
                val history = callHistoryRepository.getCallHistoryForContact(contactId)
                Log.d(TAG, "Successfully loaded ${history.size} call records")

                _callHistoryCount.value = history.size
            } catch (e: Exception) {
                Log.e(TAG, "Error loading call history", e)
                _errorMessage.value = "Failed to load call history: ${e.message}"
                _callHistoryCount.value = 0
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun callContact(withVideo: Boolean = false) {
        val contactId = _userContactId.value
        val name = _contactName.value ?: "Unknown"

        if (contactId == null) {
            Log.e(TAG, "Cannot call: Invalid contact ID")
            _errorMessage.value = "Cannot initiate call: Invalid user ID"
            return
        }

        Log.d(TAG, "Resolving profile for call to $name (Contact ID: $contactId)")
        _isCallInProgress.value = true

        viewModelScope.launch {
            try {
                val presenceResult = presenceRepository.getUserPresenceByContactId(contactId)
                if (presenceResult.isFailure) {
                    Log.e(TAG, "Failed to look up profile for contact $contactId")
                    _errorMessage.value = "Cannot find user. They may be offline."
                    _isCallInProgress.value = false
                    return@launch
                }

                val presence = presenceResult.getOrNull()
                if (presence == null) {
                    Log.e(TAG, "Profile not found for contact $contactId")
                    _errorMessage.value = "User not found."
                    _isCallInProgress.value = false
                    return@launch
                }

                Log.d(TAG, "Found profile UUID: ${presence.userId} for contact $contactId")
                _callReadyEvent.value = CallReadyParams(
                    profileId = presence.userId,
                    name = name,
                    contactId = contactId,
                    nickname = _nickname.value,
                    avatarUrl = avatarUrl,
                    withVideo = withVideo
                )

                kotlinx.coroutines.delay(500)
                _isCallInProgress.value = false

            } catch (e: Exception) {
                Log.e(TAG, "Error resolving call profile", e)
                _errorMessage.value = "Failed to initiate call: ${e.message}"
                _isCallInProgress.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun deleteContact() {
        val id = _contactId.value
        val name = _contactName.value ?: "Unknown"

        if (id.isNullOrBlank()) {
            _errorMessage.value = "Cannot delete contact: Invalid contact ID"
            return
        }

        Log.d(TAG, "Deleting contact: $name (ID: $id)")
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val success = contactRepository.deleteContact(id)

                if (success) {
                    Log.d(TAG, "Contact deleted successfully: $name")
                    _isDeleted.value = true
                } else {
                    Log.e(TAG, "Failed to delete contact: $name")
                    _errorMessage.value = "Failed to delete contact. Please try again."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting contact", e)
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetDeletedState() {
        _isDeleted.value = false
    }

    fun addToContacts() {
        val contactId = _userContactId.value
        val name = _contactName.value ?: "Unknown"
        val email = _contactEmail.value

        if (contactId == null) {
            _errorMessage.value = "Cannot add contact: Invalid contact ID"
            return
        }

        Log.d(TAG, "Adding contact: $name (Contact ID: $contactId)")
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val createdContact = contactRepository.createContact(
                    contactId = contactId,
                    email = email,
                    address = null,
                    notes = null,
                    isFavorite = false
                )

                if (createdContact != null) {
                    // Search results do not carry the saved contacts-row ID.
                    // Publish it before exposing favorite/edit actions.
                    _contactId.value = createdContact.id
                    _isFavorite.value = createdContact.isFavorite
                    _nickname.value = createdContact.nickname
                    Log.d(TAG, "Contact added successfully: $name")
                    _isAdded.value = true
                } else {
                    Log.e(TAG, "Failed to add contact: $name")
                    _errorMessage.value = "Failed to add contact. Please try again."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding contact", e)
                _errorMessage.value = "Error: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun resetAddedState() {
        _isAdded.value = false
    }

    fun toggleFavorite() {
        val id = _contactId.value
        val currentFavorite = _isFavorite.value ?: false

        if (id.isNullOrBlank()) {
            _errorMessage.value = "Cannot update favorite: Invalid contact"
            return
        }

        val newFavorite = !currentFavorite
        Log.d(TAG, "Toggling favorite for contact $id: $currentFavorite -> $newFavorite")

        viewModelScope.launch {
            try {
                if (!newFavorite) priorityRepository.remove(id)
                val success = contactRepository.updateFavoriteStatus(id, newFavorite)
                if (success) {
                    _isFavorite.value = newFavorite
                    if (!newFavorite) {
                        _isPriority.value = false
                        _isPriorityFull.value = priorityRepository.isFull()
                    }
                    Log.d(TAG, "Favorite updated successfully")
                } else {
                    _errorMessage.value = "Failed to update favorite"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling favorite", e)
                _errorMessage.value = "Error: ${e.message}"
            }
        }
    }

    fun togglePriority() {
        val id = _contactId.value ?: return
        if (_isFavorite.value != true) return
        viewModelScope.launch {
            try {
                val current = priorityRepository.getSlot(id)
                if (current > 0) {
                    priorityRepository.remove(id)
                    _isPriority.value = false
                } else {
                    if (priorityRepository.isFull()) {
                        _errorMessage.value = "Ingen ledige prioritetsplasser"
                        return@launch
                    }
                    priorityRepository.assign(id)
                    _isPriority.value = priorityRepository.getSlot(id) > 0
                }
                _isPriorityFull.value = priorityRepository.isFull()
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling priority", e)
                _errorMessage.value = "Error: ${e.message}"
            }
        }
    }

    fun updateNickname(nickname: String?) {
        val id = _contactId.value
        if (id.isNullOrBlank()) return

        val trimmed = nickname?.trim()?.ifBlank { null }
        Log.d(TAG, "Updating nickname for contact $id to '$trimmed'")

        viewModelScope.launch {
            try {
                val success = contactRepository.updateNickname(id, trimmed)
                if (success) {
                    _nickname.value = trimmed
                    Log.d(TAG, "Nickname updated successfully")
                } else {
                    _errorMessage.value = "Failed to update nickname"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating nickname", e)
                _errorMessage.value = "Error: ${e.message}"
            }
        }
    }
}
