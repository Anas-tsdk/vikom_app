package com.example.tv_caller_app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.datasource.AppointmentDataSource
import com.example.tv_caller_app.data.datasource.AppointmentResponse
import com.example.tv_caller_app.data.datasource.AppointmentRescheduleProposal
import com.example.tv_caller_app.data.datasource.BackendNotConfiguredException
import com.example.tv_caller_app.data.datasource.ProfileNotLinkedException
import com.example.tv_caller_app.data.datasource.SessionExpiredException
import com.example.tv_caller_app.data.repository.AppointmentRepository
import com.example.tv_caller_app.domain.Appointment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed class AppointmentsUiState {
    data object Loading : AppointmentsUiState()
    data class Content(val appointments: List<Appointment>) : AppointmentsUiState()
    data object Empty : AppointmentsUiState()
    data object NotLinked : AppointmentsUiState()
    data object SessionExpired : AppointmentsUiState()
    data object NotConfigured : AppointmentsUiState()
    data class Error(val message: String? = null) : AppointmentsUiState()
}

class AppointmentsViewModel(
    private val appointmentDataSource: AppointmentDataSource
) : ViewModel() {
    private val _uiState = MutableLiveData<AppointmentsUiState>(AppointmentsUiState.Loading)
    val uiState: LiveData<AppointmentsUiState> = _uiState

    private val _responseInProgress = MutableLiveData<Int?>(null)
    val responseInProgress: LiveData<Int?> = _responseInProgress

    private val _responseError = MutableLiveData<String?>(null)
    val responseError: LiveData<String?> = _responseError

    private val _responseCompletedId = MutableLiveData<Int?>(null)
    val responseCompletedId: LiveData<Int?> = _responseCompletedId

    private var loadJob: Job? = null
    private var loadGeneration = 0L

    fun load(forceRefresh: Boolean = true) {
        // MainActivity, HomeFragment and the appointments screen can all resume
        // in the same frame. Coalesce those requests instead of cancelling and
        // restarting the same network call several times.
        if (loadJob?.isActive == true) return

        val generation = ++loadGeneration
        // Keep already rendered appointments visible while refreshing in the
        // background. This makes the destination react immediately to a click.
        if (_uiState.value !is AppointmentsUiState.Content) {
            _uiState.value = AppointmentsUiState.Loading
        }
        loadJob = viewModelScope.launch {
            try {
                val appointments = appointmentDataSource.getMyAppointments(forceRefresh)
                if (generation == loadGeneration) show(appointments)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: ProfileNotLinkedException) {
                if (generation == loadGeneration) {
                    _uiState.value = AppointmentsUiState.NotLinked
                }
            } catch (_: SessionExpiredException) {
                if (generation == loadGeneration) {
                    _uiState.value = AppointmentsUiState.SessionExpired
                }
            } catch (_: BackendNotConfiguredException) {
                if (generation == loadGeneration) {
                    _uiState.value = AppointmentsUiState.NotConfigured
                }
            } catch (error: Exception) {
                Log.e("AppointmentsViewModel", "Failed to load appointments", error)
                if (generation == loadGeneration) {
                    _uiState.value = AppointmentsUiState.Error(error.message)
                }
            } finally {
                if (generation == loadGeneration) loadJob = null
            }
        }
    }

    fun respond(
        appointmentId: Int,
        response: AppointmentResponse,
        proposal: AppointmentRescheduleProposal? = null
    ) {
        if (_responseInProgress.value != null) return
        invalidatePendingLoads()
        _responseInProgress.value = appointmentId
        _responseError.value = null
        _responseCompletedId.value = null
        viewModelScope.launch {
            var reloadAfterFailure = false
            try {
                appointmentDataSource.respondToInvitation(appointmentId, response, proposal)
                val appointments = appointmentDataSource.getMyAppointments(forceRefresh = true)

                // A refresh may have been requested while the response was being sent. The
                // post-response result is authoritative, so prevent that older request from
                // publishing stale invitation state after this result.
                invalidatePendingLoads()
                show(appointments)
                _responseCompletedId.value = appointmentId
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Log.e("AppointmentsViewModel", "Failed to answer invitation", error)
                _responseError.value = error.message
                reloadAfterFailure = true
            } finally {
                _responseInProgress.value = null
                if (reloadAfterFailure) load(forceRefresh = true)
            }
        }
    }

    fun clearResponseError() {
        _responseError.value = null
    }

    fun clearResponseCompleted() {
        _responseCompletedId.value = null
    }

    fun invalidateAndLoad() {
        appointmentDataSource.invalidateCache()
        load(forceRefresh = true)
    }

    private fun show(appointments: List<Appointment>) {
        _uiState.value = if (appointments.isEmpty()) {
            AppointmentsUiState.Empty
        } else {
            AppointmentsUiState.Content(appointments)
        }
    }

    private fun invalidatePendingLoads() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
    }
}

class AppointmentsViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val app = application as TVCallerApplication
        val sessionManager = SessionManager.getInstance(app)
        return AppointmentsViewModel(
            AppointmentRepository.getInstance(sessionManager, app.getAuthRepository())
        ) as T
    }
}
