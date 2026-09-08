package com.example.tv_caller_app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.tv_caller_app.TVCallerApplication

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepository = getApplication<TVCallerApplication>().getAuthRepository()

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _sessionInfo = MutableLiveData<Map<String, String>>()
    val sessionInfo: LiveData<Map<String, String>> = _sessionInfo

    fun loadSessionInfo() {
        _sessionInfo.value = authRepository.getSessionInfo()
    }
}
