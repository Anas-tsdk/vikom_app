package com.example.tv_caller_app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.domain.UsernameNotUniqueException

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val profileRepository = getApplication<TVCallerApplication>().getProfileRepository()

    companion object {
        private const val TAG = "ProfileViewModel"
    }

    private val _username = MutableLiveData<String>()
    val username: LiveData<String> = _username

    private val _email = MutableLiveData<String>()
    val email: LiveData<String> = _email

    private val _contactId = MutableLiveData<Int>()
    val contactId: LiveData<Int> = _contactId

    private val _avatarUrl = MutableLiveData<String?>()
    val avatarUrl: LiveData<String?> = _avatarUrl

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private val _successMessage = MutableLiveData<String?>()
    val successMessage: LiveData<String?> = _successMessage

    fun loadProfile() {
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val profile = profileRepository.getProfile()
                if (profile != null) {
                    _username.value = profile.username ?: ""
                    _email.value = profile.email ?: profileRepository.getStoredEmail() ?: ""
                    _contactId.value = profile.contactId
                    _avatarUrl.value = profile.avatarUrl
                    Log.d(TAG, "Profile loaded: ${profile.username}")
                } else {
                    _email.value = profileRepository.getStoredEmail() ?: ""
                    _errorMessage.value = "Kunne ikke laste profil"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading profile", e)
                _errorMessage.value = "Feil: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateUsername(newUsername: String) {
        val trimmed = newUsername.trim()
        if (trimmed.isBlank()) {
            _errorMessage.value = "Brukernavn kan ikke være tomt"
            return
        }
        if (trimmed.length < 3) {
            _errorMessage.value = "Brukernavn må være minst 3 tegn"
            return
        }
        if (trimmed == _username.value) {
            return
        }

        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val result = profileRepository.updateUsername(trimmed)
                if (result.isSuccess) {
                    _username.value = trimmed
                    _successMessage.value = "Brukernavn oppdatert"
                    Log.d(TAG, "Username updated to: $trimmed")
                } else {
                    val error = result.exceptionOrNull()
                    _errorMessage.value = if (error is UsernameNotUniqueException) {
                        error.message
                    } else {
                        "Kunne ikke oppdatere brukernavn"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating username", e)
                _errorMessage.value = "Feil: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun uploadAvatar(imageBytes: ByteArray) {
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val url = profileRepository.uploadAvatar(imageBytes)
                _avatarUrl.value = url
                _successMessage.value = "Profilbilde oppdatert"
                Log.d(TAG, "Avatar uploaded: $url")
            } catch (e: Exception) {
                Log.e(TAG, "Error uploading avatar", e)
                _errorMessage.value = "Upload failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setPresetAvatar(avatarName: String) {
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val success = profileRepository.setPresetAvatar(avatarName)
                if (success) {
                    _avatarUrl.value = "preset:$avatarName"
                    _successMessage.value = "Avatar oppdatert"
                    Log.d(TAG, "Preset avatar set: $avatarName")
                } else {
                    _errorMessage.value = "Kunne ikke sette avatar"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting preset avatar", e)
                _errorMessage.value = "Feil: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeAvatar() {
        _isLoading.value = true
        _errorMessage.value = null

        viewModelScope.launch {
            try {
                val success = profileRepository.deleteAvatar()
                if (success) {
                    _avatarUrl.value = null
                    _successMessage.value = "Profilbilde fjernet"
                } else {
                    _errorMessage.value = "Kunne ikke fjerne bilde"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing avatar", e)
                _errorMessage.value = "Feil: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearSuccess() {
        _successMessage.value = null
    }
}
