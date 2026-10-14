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
import com.example.tv_caller_app.calling.service.AppointmentNotificationManager
import com.example.tv_caller_app.calling.service.CallNotificationManager
import com.example.tv_caller_app.calling.service.MissedCallNotificationManager
import com.example.tv_caller_app.calling.service.SignalingForegroundService
import com.example.tv_caller_app.calling.signaling.SignalingManager
import com.example.tv_caller_app.calling.IncomingCallEvent
import com.example.tv_caller_app.calling.webrtc.WebRTCManager
import com.example.tv_caller_app.data.datasource.AuthDataSource
import com.example.tv_caller_app.data.datasource.LocalSessionDataSource
import com.example.tv_caller_app.data.remote.SupabaseClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import com.example.tv_caller_app.data.datasource.CallDataSource
import com.example.tv_caller_app.data.datasource.CallHistoryDataSource
import com.example.tv_caller_app.data.datasource.ContactDataSource
import com.example.tv_caller_app.data.datasource.PresenceDataSource
import com.example.tv_caller_app.data.datasource.AvatarDataSource
import com.example.tv_caller_app.data.datasource.ProfileDataSource
import com.example.tv_caller_app.domain.usecase.GetQuickDialEntriesUseCase
import com.example.tv_caller_app.domain.usecase.UpdateAvatarUseCase
import com.example.tv_caller_app.domain.AppointmentEvent
import com.example.tv_caller_app.domain.IncomingCallOffer
import com.example.tv_caller_app.data.repository.AppointmentRepository
import com.example.tv_caller_app.data.repository.AuthRepository
import com.example.tv_caller_app.data.repository.CallHistoryRepository
import com.example.tv_caller_app.data.repository.CallRepository
import com.example.tv_caller_app.data.repository.ContactRepository
import com.example.tv_caller_app.data.repository.DailyCheckInRepository
import com.example.tv_caller_app.data.repository.FcmTokenRepository
import com.example.tv_caller_app.data.repository.PatientProfileRepository
import com.example.tv_caller_app.data.repository.PresenceRepository
import com.example.tv_caller_app.data.repository.ProfileRepository
import com.example.tv_caller_app.data.datasource.PriorityDataSource
import com.example.tv_caller_app.data.repository.PriorityRepository
import com.example.tv_caller_app.data.repository.QuickDialRepository
import com.example.tv_caller_app.ui.viewmodel.CallViewModel
import com.example.tv_caller_app.ui.util.TvFocusHighlighter
import com.example.tv_caller_app.ui.util.TvWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.messaging.FirebaseMessaging

class TVCallerApplication : Application(), DefaultLifecycleObserver {

    private val TAG = "TVCallerApplication"
    private var sessionRefreshManager: SessionRefreshManager? = null
    private var presenceRepository: PresenceRepository? = null
    private var callViewModel: CallViewModel? = null
    private var callRepository: CallRepository? = null

    private val pushAppointmentEvents = MutableSharedFlow<AppointmentEvent>(extraBufferCapacity = 8)

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super<Application>.onCreate()
        Log.d(TAG, "TVCallerApplication starting...")

        // One high-contrast focus system for every TV/remote navigable screen.
        registerActivityLifecycleCallbacks(TvFocusHighlighter)
        registerActivityLifecycleCallbacks(TvWindowManager)

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
            registerFcmToken()
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

    fun getDailyCheckInRepository(): DailyCheckInRepository =
        DailyCheckInRepository.getInstance(SessionManager.getInstance(this))

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
                    signalingService = SignalingManager(
                        sessionManager.getUserId() ?: "",
                        SupabaseClient.client,
                        applicationContext
                    ),
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

    fun getAppointmentEventFlow(): Flow<AppointmentEvent> =
        callRepository?.appointmentEvents?.let { merge(it, pushAppointmentEvents) }
            ?: pushAppointmentEvents

    fun getCallRepository(): CallRepository? = callRepository

    /**
     * Queues a Firebase offer into the already existing realtime/WebRTC call
     * stack. Starting the foreground service first keeps the process alive and
     * lets its normal StateFlow observer display the full-screen call alert.
     */
    fun enqueueIncomingCallFromPush(offer: IncomingCallOffer) {
        val sessionManager = SessionManager.getInstance(this)
        if (!sessionManager.isLoggedIn()) {
            Log.w(TAG, "Ignoring incoming call push while signed out")
            return
        }

        var foregroundServiceStartFailed = false
        try {
            SignalingForegroundService.startService(this)
        } catch (error: Exception) {
            foregroundServiceStartFailed = true
            Log.w(TAG, "Could not start signaling foreground service from Firebase", error)
        }

        applicationScope.launch {
            try {
                withContext(Dispatchers.IO) { getAuthRepository().restoreSession() }
                initializeCallViewModel()
                val accepted = callRepository?.injectIncomingCallOffer(offer) ?: false
                if (!accepted) {
                    Log.d(TAG, "Firebase incoming offer was duplicate or the call stack was unavailable")
                } else if (foregroundServiceStartFailed) {
                    // The service normally owns this notification. If Android
                    // refuses its background start, keep the accepted call
                    // reachable without creating another signaling/WebRTC stack.
                    SignalingForegroundService.postIncomingCallNotification(
                        this@TVCallerApplication,
                        IncomingCallEvent(
                            callerId = offer.contactId?.toString() ?: offer.callerId,
                            callerName = offer.callerName,
                            nickname = offer.nickname,
                            avatarUrl = offer.avatarUrl,
                            mediaType = offer.mediaType
                        )
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to inject Firebase incoming call", error)
            }
        }
    }

    /** Wakes and restores the realtime listener for a small, SDP-free call push. */
    fun wakeCallListenerFromPush() {
        val sessionManager = SessionManager.getInstance(this)
        if (!sessionManager.isLoggedIn()) {
            Log.w(TAG, "Ignoring incoming call wake signal while signed out")
            return
        }
        try {
            SignalingForegroundService.startService(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not start signaling service from call wake signal", error)
        }
        applicationScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { getAuthRepository().restoreSession() }
                initializeCallViewModel()
            }.onFailure { error ->
                Log.e(TAG, "Unable to restore call listener from Firebase", error)
            }
        }
    }

    /** Handles appointment data pushes even when the realtime listener is asleep. */
    fun handleAppointmentPush(event: AppointmentEvent) {
        val sessionManager = SessionManager.getInstance(this)
        if (!sessionManager.isLoggedIn()) {
            Log.w(TAG, "Ignoring appointment push while signed out")
            return
        }
        val repository = AppointmentRepository.getInstance(sessionManager, getAuthRepository())
        repository.invalidateCache()
        // Publish a notification before returning from Firebase's short-lived
        // callback. ID-only messages use a generic line and the details screen
        // loads authoritative data from the backend.
        pushAppointmentEvents.tryEmit(event)
        AppointmentNotificationManager.show(this, event)
        applicationScope.launch {
            val resolvedEvent = if (
                event.date.isNotBlank() &&
                event.startTime.isNotBlank() &&
                event.endTime.isNotBlank() &&
                event.status.isNotBlank() &&
                event.patientResponseStatus.isNotBlank()
            ) {
                event
            } else {
                runCatching {
                    repository.getMyAppointments(forceRefresh = true)
                        .firstOrNull { it.id == event.appointmentId }
                        ?.let { appointment ->
                            event.copy(
                                date = appointment.date.orEmpty(),
                                startTime = appointment.startTime.orEmpty(),
                                endTime = appointment.endTime.orEmpty(),
                                personnelName = appointment.personnelName.orEmpty(),
                                status = appointment.status,
                                patientResponseStatus = appointment.patientResponseStatus
                            )
                        }
                }.onFailure { error ->
                    Log.w(TAG, "Could not enrich appointment push from the backend", error)
                }.getOrNull() ?: event
            }

            if (resolvedEvent != event) {
                AppointmentNotificationManager.show(this@TVCallerApplication, resolvedEvent)
            }
        }
    }

    fun handleMissedCallPush(callId: String, callerName: String, mediaType: String) {
        MissedCallNotificationManager.show(this, callId, callerName, mediaType)
        applicationScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { getAuthRepository().restoreSession() }
                initializeCallViewModel()
                callViewModel?.reportMissedCall(callId, callerName, mediaType)
            }.onFailure { error ->
                Log.w(TAG, "Could not publish missed-call message inside the app", error)
            }
        }
    }

    fun updateFcmToken(token: String) {
        if (!BuildConfig.FIREBASE_CONFIGURED || token.isBlank()) return
        applicationScope.launch(Dispatchers.IO) {
            val sessionManager = SessionManager.getInstance(this@TVCallerApplication)
            if (!sessionManager.isLoggedIn()) return@launch
            try {
                getAuthRepository().restoreSession()
                FcmTokenRepository.getInstance(sessionManager).updateToken(token)
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "Firebase token could not be stored (${error::class.simpleName})"
                        )
                    }
            } catch (error: Exception) {
                Log.e(TAG, "Firebase token registration failed", error)
            }
        }
    }

    suspend fun clearFcmToken() {
        if (!BuildConfig.FIREBASE_CONFIGURED) return
        withContext(Dispatchers.IO) {
            FcmTokenRepository.getInstance(SessionManager.getInstance(this@TVCallerApplication))
                .clearToken()
        }
    }

    private fun registerFcmToken() {
        if (!BuildConfig.FIREBASE_CONFIGURED) {
            Log.d(TAG, "Firebase push disabled: app/google-services.json is not present")
            return
        }
        if (!SessionManager.getInstance(this).isLoggedIn()) return

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Unable to obtain Firebase token", task.exception)
                    return@addOnCompleteListener
                }
                task.result?.let(::updateFcmToken)
            }
        } catch (error: Exception) {
            // A malformed or mismatched google-services.json must not break
            // realtime calling or application startup.
            Log.w(TAG, "Firebase is present but could not be initialized", error)
        }
    }

    fun invalidateAllCaches() {
        val sessionManager = SessionManager.getInstance(this)
        getContactRepository().invalidateCache()
        CallHistoryRepository.getInstance(CallHistoryDataSource.getInstance(sessionManager)).invalidateCache()
        getQuickDialRepository().invalidateCache()
        AppointmentRepository.getInstance(sessionManager, getAuthRepository()).invalidateCache()
        PatientProfileRepository.getInstance(sessionManager, getAuthRepository()).invalidateCache()
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
        val viewModelToDispose = callViewModel
        callViewModel = null
        callRepository = null
        applicationScope.launch(Dispatchers.IO) {
            viewModelToDispose?.dispose()
        }
        Log.d(TAG, "CallViewModel cleaned up")
    }

    fun resetCallStackForReconnect() {
        val activeState = callViewModel?.callState?.value
        val isCallActive = activeState is CallViewModel.CallState.Connected ||
                           activeState is CallViewModel.CallState.Connecting ||
                           activeState is CallViewModel.CallState.RingingIncoming ||
                           activeState is CallViewModel.CallState.RingingOutgoing
        if (!isCallActive) {
            val viewModelToDispose = callViewModel
            callViewModel = null
            callRepository = null
            applicationScope.launch(Dispatchers.IO) { viewModelToDispose?.dispose() }
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
            val viewModelToDispose = callViewModel
            callViewModel = null
            callRepository = null
            Log.d(TAG, "Call stack cleared for boot-time reconnect")
            if (viewModelToDispose != null) {
                return applicationScope.launch(Dispatchers.IO) {
                    try {
                        viewModelToDispose.dispose()
                        Log.d(TAG, "Old call stack disposed for reconnect")
                    } catch (e: Exception) {
                        Log.w(TAG, "Old call stack cleanup failed: ${e.message}")
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
