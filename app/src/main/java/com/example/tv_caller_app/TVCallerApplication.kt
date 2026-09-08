package com.example.tv_caller_app

import android.app.Application
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.tv_caller_app.calling.service.ScreenOffReceiver
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.auth.SessionRefreshManager
import com.example.tv_caller_app.calling.audio.AudioRouteManager
import com.example.tv_caller_app.calling.service.CallNotificationManager
import com.example.tv_caller_app.calling.service.SignalingForegroundService
import com.example.tv_caller_app.calling.signaling.SignalingManager
import com.example.tv_caller_app.calling.IncomingCallEvent
import com.example.tv_caller_app.calling.webrtc.WebRTCManager
import com.example.tv_caller_app.data.datasource.AuthDataSource
import com.example.tv_caller_app.data.datasource.LocalSessionDataSource
import com.example.tv_caller_app.data.remote.SupabaseClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.example.tv_caller_app.data.datasource.CallDataSource
import com.example.tv_caller_app.data.datasource.CallHistoryDataSource
import com.example.tv_caller_app.data.datasource.ContactDataSource
import com.example.tv_caller_app.data.datasource.PresenceDataSource
import com.example.tv_caller_app.data.datasource.AvatarDataSource
import com.example.tv_caller_app.data.datasource.ProfileDataSource
import com.example.tv_caller_app.domain.usecase.GetQuickDialEntriesUseCase
import com.example.tv_caller_app.domain.usecase.UpdateAvatarUseCase
import com.example.tv_caller_app.data.repository.AuthRepository
import com.example.tv_caller_app.data.repository.CallHistoryRepository
import com.example.tv_caller_app.data.repository.CallRepository
import com.example.tv_caller_app.data.repository.ContactRepository
import com.example.tv_caller_app.data.repository.PresenceRepository
import com.example.tv_caller_app.data.repository.ProfileRepository
import com.example.tv_caller_app.data.datasource.PriorityDataSource
import com.example.tv_caller_app.data.repository.PriorityRepository
import com.example.tv_caller_app.data.repository.QuickDialRepository
import com.example.tv_caller_app.ui.viewmodel.CallViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TVCallerApplication : Application(), DefaultLifecycleObserver {

    private val TAG = "TVCallerApplication"
    private var sessionRefreshManager: SessionRefreshManager? = null
    private var presenceRepository: PresenceRepository? = null
    private var callViewModel: CallViewModel? = null
    private var callRepository: CallRepository? = null

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super<Application>.onCreate()
        Log.d(TAG, "TVCallerApplication starting...")

        initializeSessionManager()

        initializeSessionRefresh()

        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val screenFilter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(ScreenOffReceiver(), screenFilter)

        Log.d(TAG, "TVCallerApplication initialized successfully")
    }

    fun startSessionRefresh() {
        try {
            if (sessionRefreshManager == null) {
                val sessionManager = SessionManager.getInstance(this)
                val authRepository = AuthRepository.getInstance(
                    AuthDataSource.getInstance(sessionManager),
                    LocalSessionDataSource.getInstance(sessionManager),
                    ProfileDataSource.getInstance(sessionManager)
                )
                sessionRefreshManager = SessionRefreshManager(authRepository, sessionManager)
            }
            sessionRefreshManager?.startPeriodicRefresh()
            Log.d(TAG, "SessionRefreshManager started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SessionRefreshManager", e)
        }
    }

    fun stopSessionRefresh() {
        try {
            sessionRefreshManager?.stop()
            Log.d(TAG, "SessionRefreshManager stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop SessionRefreshManager", e)
        }
    }

    fun startPresence(deviceType: String = "tv") {
        applicationScope.launch {
            try {
                val result = getPresenceRepository().setOnline(deviceType)
                if (result.isSuccess) {
                    Log.d(TAG, "Presence started successfully")
                } else {
                    Log.e(TAG, "Failed to start presence: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start presence", e)
            }
        }
    }

    fun stopPresence() {
        applicationScope.launch {
            try {
                val result = presenceRepository?.setOffline()
                if (result?.isSuccess == true) {
                    Log.d(TAG, "Presence stopped successfully")
                } else {
                    Log.e(TAG, "Failed to stop presence: ${result?.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop presence", e)
            }
        }
    }

    fun getPresenceRepository(): PresenceRepository {
        val sessionManager = SessionManager.getInstance(this)
        return presenceRepository
            ?: PresenceRepository.getInstance(
                PresenceDataSource.getInstance(sessionManager),
                ProfileDataSource.getInstance(sessionManager)
            ).also { presenceRepository = it }
    }

    fun getContactRepository(): ContactRepository {
        val sessionManager = SessionManager.getInstance(this)
        return ContactRepository.getInstance(
            ContactDataSource.getInstance(sessionManager),
            ProfileDataSource.getInstance(sessionManager)
        )
    }

    fun getCallHistoryRepository(): CallHistoryRepository =
        CallHistoryRepository.getInstance(CallHistoryDataSource.getInstance(SessionManager.getInstance(this)))

    fun getProfileRepository(): ProfileRepository {
        val sessionManager = SessionManager.getInstance(this)
        val profileDataSource = ProfileDataSource.getInstance(sessionManager)
        val updateAvatarUseCase = UpdateAvatarUseCase(
            avatarDataSource = AvatarDataSource.getInstance(sessionManager),
            profileDataSource = profileDataSource
        )
        return ProfileRepository.getInstance(profileDataSource, updateAvatarUseCase)
    }

    fun getQuickDialRepository(): QuickDialRepository {
        val sessionManager = SessionManager.getInstance(this)
        val useCase = GetQuickDialEntriesUseCase(
            contactRepository = getContactRepository(),
            callHistoryRepository = CallHistoryRepository.getInstance(CallHistoryDataSource.getInstance(sessionManager)),
            presenceRepository = getPresenceRepository()
        )
        return QuickDialRepository.getInstance(useCase)
    }

    @Synchronized fun initializeCallViewModel() {
        try {
            if (callViewModel != null) {
                Log.d(TAG, "initializeCallViewModel: already initialized — skipping (no double-init)")
                return
            }
            Log.d(TAG, "initializeCallViewModel: creating new CallViewModel + SignalingManager")
            val sessionManager = SessionManager.getInstance(this)
            val repo = CallRepository(
                callDataSource = CallDataSource(
                    sessionManager = sessionManager,
                    signalingService = SignalingManager(sessionManager.getUserId() ?: "", SupabaseClient.client),
                    webRTCManager = WebRTCManager(applicationContext),
                    callNotificationDataSource = CallNotificationManager(applicationContext),
                    audioRouteManager = AudioRouteManager(applicationContext)
                ),
                presenceRepository = getPresenceRepository(),
                contactRepository = getContactRepository(),
                callHistoryRepository = CallHistoryRepository.getInstance(CallHistoryDataSource.getInstance(sessionManager)),
                quickDialRepository = getQuickDialRepository()
            )
            callRepository = repo
            callViewModel = CallViewModel()
            callViewModel?.initialize(callingRepository = repo)
            Log.d(TAG, "initializeCallViewModel: done")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CallViewModel", e)
        }
    }

    fun getAuthRepository(): AuthRepository {
        val sessionManager = SessionManager.getInstance(this)
        return AuthRepository.getInstance(
            AuthDataSource.getInstance(sessionManager),
            LocalSessionDataSource.getInstance(sessionManager),
            ProfileDataSource.getInstance(sessionManager)
        )
    }

    fun getPriorityRepository(): PriorityRepository {
        val sessionManager = SessionManager.getInstance(this)
        return PriorityRepository.getInstance(PriorityDataSource.getInstance(sessionManager))
    }

    fun getCallViewModel(): CallViewModel? = callViewModel

    fun getPendingIncomingCallFlow(): Flow<IncomingCallEvent?> =
        callRepository?.pendingIncomingCall?.map { data ->
            data?.let { IncomingCallEvent(it.callerId, it.callerName, it.nickname, it.avatarUrl, it.mediaType) }
        } ?: kotlinx.coroutines.flow.flowOf(null)

    fun getCallRepository(): CallRepository? = callRepository

    fun invalidateAllCaches() {
        val sessionManager = SessionManager.getInstance(this)
        getContactRepository().invalidateCache()
        CallHistoryRepository.getInstance(CallHistoryDataSource.getInstance(sessionManager)).invalidateCache()
        getQuickDialRepository().invalidateCache()
        Log.d(TAG, "All caches invalidated")
    }

    fun handleScreenOn() {
        applicationScope.launch {
            val sessionManager = SessionManager.getInstance(this@TVCallerApplication)
            if (!sessionManager.isLoggedIn()) return@launch
            try {
                val authRepo = getAuthRepository()
                authRepo.restoreSession()
                if (!sessionManager.hasRecentSession(maxAgeHours = 1)) {
                    authRepo.refreshSession()
                        .onFailure { Log.w(TAG, "Screen-on session refresh failed: ${it.message}") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Screen-on session recovery failed: ${e.message}")
            }
            startPresence()
        }
    }

    fun cleanupCallViewModel() {
        SignalingForegroundService.stopService(this)
        callViewModel = null
        callRepository = null
        Log.d(TAG, "CallViewModel cleaned up")
    }

    fun resetCallStackForReconnect() {
        val activeState = callViewModel?.callState?.value
        val isCallActive = activeState is CallViewModel.CallState.Connected ||
                           activeState is CallViewModel.CallState.Connecting ||
                           activeState is CallViewModel.CallState.RingingIncoming ||
                           activeState is CallViewModel.CallState.RingingOutgoing
        if (!isCallActive) {
            callViewModel = null
            callRepository = null
            Log.d(TAG, "Call stack reset for screen-on reconnect")
        } else {
            Log.d(TAG, "Call stack not reset — active call in progress: $activeState")
        }
    }

    fun cleanupCallStackForReconnect(): Job {
        val activeState = callViewModel?.callState?.value
        val isCallActive = activeState is CallViewModel.CallState.Connected ||
                           activeState is CallViewModel.CallState.Connecting ||
                           activeState is CallViewModel.CallState.RingingIncoming ||
                           activeState is CallViewModel.CallState.RingingOutgoing
        if (!isCallActive) {
            val repoToCleanup = callRepository
            callViewModel = null
            callRepository = null
            Log.d(TAG, "Call stack cleared for boot-time reconnect")
            if (repoToCleanup != null) {
                return applicationScope.launch(Dispatchers.IO) {
                    try {
                        repoToCleanup.cleanupSignaling()
                        Log.d(TAG, "Old SignalingManager unsubscribed for reconnect")
                    } catch (e: Exception) {
                        Log.w(TAG, "Old SignalingManager cleanup failed: ${e.message}")
                    }
                }
            }
        } else {
            Log.d(TAG, "Call stack not reset — active call in progress: $activeState")
        }
        return Job().also { it.complete() }
    }

    fun handleScreenOnAndReconnect(context: android.content.Context) {
        val cleanupJob = cleanupCallStackForReconnect()
        applicationScope.launch {
            cleanupJob.join()
            handleScreenOn()
            SignalingForegroundService.startService(context)
        }
    }

    private fun initializeSessionManager() {
        try {
            SessionManager.getInstance(this)
            Log.d(TAG, "SessionManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SessionManager", e)
            throw RuntimeException("Failed to initialize secure storage", e)
        }
    }

    private fun initializeSessionRefresh() {
        try {
            val sessionManager = SessionManager.getInstance(this)

            if (sessionManager.isLoggedIn()) {
                startSessionRefresh()
                Log.d(TAG, "SessionRefreshManager started (user logged in)")
            } else {
                Log.d(TAG, "SessionRefreshManager not started (no active session)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SessionRefreshManager", e)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.d(TAG, "App moved to foreground")

        val sessionManager = SessionManager.getInstance(this)
        if (sessionManager.isLoggedIn()) {
            applicationScope.launch {
                try {
                    val authRepo = getAuthRepository()
                    authRepo.restoreSession()
                    if (!sessionManager.hasRecentSession(maxAgeHours = 1)) {
                        authRepo.refreshSession()
                        Log.d(TAG, "Session refreshed on foreground")
                    } else {
                        Log.d(TAG, "Session is recent — skipping network refresh")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Session restore/refresh on foreground failed: ${e.message}")
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.d(TAG, "App moved to background - presence stays active via foreground service")
    }

    override fun onTerminate() {
        super<Application>.onTerminate()
        Log.d(TAG, "App process terminating")

        val sessionManager = SessionManager.getInstance(this)
        if (sessionManager.isLoggedIn()) {
            stopPresence()
        }
    }
}
