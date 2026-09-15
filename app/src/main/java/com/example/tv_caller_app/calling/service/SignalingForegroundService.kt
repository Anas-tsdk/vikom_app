package com.example.tv_caller_app.calling.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.calling.permissions.PermissionHelper
import com.example.tv_caller_app.calling.CallIntentExtras
import com.example.tv_caller_app.calling.IncomingCallEvent
import com.example.tv_caller_app.ui.activities.IncomingCallActivity
import com.example.tv_caller_app.data.repository.AppointmentRepository
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignalingForegroundService : Service() {

    companion object {
        private const val TAG = "SignalingFgService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "signaling_service_channel"
        private const val CHANNEL_NAME = "Call Listening"
        private const val INCOMING_CALL_CHANNEL_ID = "incoming_call_channel"
        private const val INCOMING_CALL_CHANNEL_NAME = "Incoming Calls"
        private const val INCOMING_CALL_NOTIFICATION_ID = 1003

        private const val ACTION_START = "com.example.tv_caller_app.ACTION_START_SIGNALING"
        private const val ACTION_STOP = "com.example.tv_caller_app.ACTION_STOP_SIGNALING"
        private const val PRESENCE_HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L
        private const val INCOMING_CALL_NOTIFICATION_TIMEOUT_MS = 60_000L

        fun startService(context: Context) {
            val intent = Intent(context, SignalingForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Service start requested")
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, SignalingForegroundService::class.java))
            Log.d(TAG, "Service stop requested")
        }

        /**
         * Posts the same incoming-call notification used by the foreground
         * listener. This is also safe to call when Android refuses to start
         * that listener from the background.
         */
        fun postIncomingCallNotification(context: Context, event: IncomingCallEvent): Boolean {
            if (!PermissionHelper.hasNotificationPermission(context)) {
                Log.w(TAG, "Cannot show incoming call notification without notification permission")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        INCOMING_CALL_CHANNEL_ID,
                        INCOMING_CALL_CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = context.getString(R.string.incoming_call)
                        setShowBadge(true)
                        enableVibration(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
                )
            }

            val fullScreenIntent = IncomingCallActivity.createIntent(
                context = context,
                callerId = event.callerId,
                callerName = event.callerName,
                callId = event.callerId + "_" + System.currentTimeMillis(),
                nickname = event.nickname,
                avatarUrl = event.avatarUrl,
                callType = event.mediaType
            )
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                1,
                fullScreenIntent,
                pendingIntentFlags
            )
            val notification = NotificationCompat.Builder(context, INCOMING_CALL_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.incoming_call))
                .setContentText(
                    context.getString(R.string.notification_incoming_call_from, event.callerName)
                )
                .setSmallIcon(R.drawable.ic_call)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .setTimeoutAfter(INCOMING_CALL_NOTIFICATION_TIMEOUT_MS)
                .build()

            return runCatching {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)
            }.onFailure { error ->
                Log.e(TAG, "Could not show incoming call notification", error)
            }.isSuccess
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var incomingCallJob: Job? = null
    private var appointmentEventJob: Job? = null
    private var presenceHeartbeatJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val serviceStartTime = System.currentTimeMillis()
    private var bootReconnectDone = false
    @Volatile private var isAsyncInitInProgress = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SignalingForegroundService created")
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stopping signaling foreground service")
                stopIncomingCallObserver()
                appointmentEventJob?.cancel()
                appointmentEventJob = null
                dismissIncomingCallNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
            else -> {
                Log.i(TAG, "Starting signaling foreground service (action=${intent?.action})")
                if (!PermissionHelper.hasNotificationPermission(this)) {
                    Log.w(TAG, "POST_NOTIFICATIONS permission not granted — persistent notification may not show on Android 13+")
                }
                startForeground(NOTIFICATION_ID, buildPersistentNotification())
                observeCallState()
                startPresenceHeartbeat()
                registerNetworkCallback()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed — rescheduling service restart")
        val sessionManager = SessionManager.getInstance(this)
        if (!sessionManager.isLoggedIn()) return
        startService(this)
    }

    override fun onDestroy() {
        stopIncomingCallObserver()
        appointmentEventJob?.cancel()
        appointmentEventJob = null
        presenceHeartbeatJob?.cancel()
        unregisterNetworkCallback()
        dismissIncomingCallNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
        Log.d(TAG, "SignalingForegroundService destroyed")
    }

    private fun observeCallState() {
        val app = application as? TVCallerApplication ?: return

        if (app.getCallViewModel() != null) {
            attachCallObserver(app)
            return
        }

        val sessionManager = SessionManager.getInstance(this)
        if (!sessionManager.isLoggedIn()) {
            Log.w(TAG, "User not logged in — cannot observe call state")
            return
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (!isNetworkValidated(cm)) {
            Log.d(TAG, "Network not yet validated — deferring init to network callback")
            serviceScope.launch {
                withContext(Dispatchers.IO) {
                    try { app.getAuthRepository().restoreSession() }
                    catch (e: Exception) { Log.w(TAG, "Session restore failed: ${e.message}") }
                }
            }
            return
        }

        if (isAsyncInitInProgress) {
            Log.w(TAG, "observeCallState: async init already in progress — skipping duplicate (onStartCommand fired twice)")
            return
        }

        Log.d(TAG, "Network validated, CallViewModel null — starting async init")
        isAsyncInitInProgress = true
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    try { app.getAuthRepository().restoreSession() }
                    catch (e: Exception) { Log.w(TAG, "Session restore failed: ${e.message}") }
                }
                app.initializeCallViewModel()
                app.startPresence()
                attachCallObserver(app)
                launch(Dispatchers.IO) {
                    try {
                        val authRepo = app.getAuthRepository()
                        authRepo.refreshSession()
                            .onSuccess { Log.d(TAG, "Background session refresh completed") }
                            .onFailure { Log.w(TAG, "Background session refresh failed: ${it.message}") }
                        app.startSessionRefresh()
                    } catch (e: Exception) {
                        Log.w(TAG, "Background session refresh failed: ${e.message}")
                    }
                }
            } finally {
                isAsyncInitInProgress = false
            }
        }
    }

    private fun attachCallObserver(app: TVCallerApplication) {
        stopIncomingCallObserver()
        incomingCallJob = serviceScope.launch {
            app.getPendingIncomingCallFlow().collect { event ->
                if (event != null) {
                    Log.i(TAG, "Incoming call detected in service - launching IncomingCallActivity")
                    launchIncomingCallActivity(event)
                    showIncomingCallNotification(event)
                } else {
                    dismissIncomingCallNotification()
                }
            }
        }
        appointmentEventJob?.cancel()
        appointmentEventJob = serviceScope.launch {
            app.getAppointmentEventFlow().collect { event ->
                Log.i(TAG, "Appointment ${event.appointmentId} changed (${event.action})")
                AppointmentRepository.getInstance(
                    SessionManager.getInstance(this@SignalingForegroundService),
                    app.getAuthRepository()
                ).invalidateCache()
                AppointmentNotificationManager.show(this@SignalingForegroundService, event)
            }
        }
    }

    private fun stopIncomingCallObserver() {
        incomingCallJob?.cancel()
        incomingCallJob = null
    }

    private fun startPresenceHeartbeat() {
        presenceHeartbeatJob?.cancel()
        presenceHeartbeatJob = serviceScope.launch {
            while (true) {
                delay(PRESENCE_HEARTBEAT_INTERVAL_MS)
                val sessionManager = SessionManager.getInstance(this@SignalingForegroundService)
                if (!sessionManager.isLoggedIn()) continue
                (application as? TVCallerApplication)?.startPresence()
                Log.d(TAG, "Presence heartbeat sent")
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (isNetworkValidated(cm)) {
            Log.d(TAG, "Network already validated at service start — boot reconnect not needed")
            return
        }
        if (networkCallback != null) {
            Log.d(TAG, "Network callback already registered — skipping duplicate registration")
            return
        }
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val uptimeMs = System.currentTimeMillis() - serviceStartTime
                if (uptimeMs > 3 * 60 * 1000L || bootReconnectDone) return
                bootReconnectDone = true
                Log.i(TAG, "Validated network appeared within boot window (${uptimeMs}ms) — scheduling signaling init in 5s")
                serviceScope.launch {
                    delay(5_000L)
                    val app = application as? TVCallerApplication ?: return@launch
                    if (!SessionManager.getInstance(this@SignalingForegroundService).isLoggedIn()) return@launch
                    Log.i(TAG, "Executing boot-time signaling init after network ready")
                    val cleanupJob = app.cleanupCallStackForReconnect()
                    withContext(Dispatchers.IO) {
                        cleanupJob.join()
                        try { app.getAuthRepository().restoreSession() }
                        catch (e: Exception) { Log.w(TAG, "Session restore in network callback failed: ${e.message}") }
                    }
                    app.initializeCallViewModel()
                    app.startPresence()
                    attachCallObserver(app)
                    launch(Dispatchers.IO) {
                        try {
                            app.getAuthRepository().refreshSession()
                                .onSuccess { Log.d(TAG, "Boot-time background session refresh completed") }
                                .onFailure { Log.w(TAG, "Boot-time background session refresh failed: ${it.message}") }
                            app.startSessionRefresh()
                        } catch (e: Exception) {
                            Log.w(TAG, "Boot-time background session refresh failed: ${e.message}")
                        }
                    }
                }
            }
        }
        val requestBuilder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        try {
            cm.registerNetworkCallback(requestBuilder.build(), networkCallback!!)
            Log.d(TAG, "Boot-time network callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun isNetworkValidated(cm: ConnectivityManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork ?: return false
            val nc = cm.getNetworkCapabilities(activeNetwork) ?: return false
            return nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback: ${e.message}")
            }
            networkCallback = null
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val signalingChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_ready_for_calls)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(signalingChannel)

            val incomingCallChannel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                INCOMING_CALL_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.incoming_call)
                setShowBadge(true)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(incomingCallChannel)
        }
    }

    private fun buildPersistentNotification(): Notification {
        val activityIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent()
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_ready_for_calls))
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun buildIncomingCallIntent(event: IncomingCallEvent): Intent =
        IncomingCallActivity.createIntent(
            context = this,
            callerId = event.callerId,
            callerName = event.callerName,
            callId = event.callerId + "_" + System.currentTimeMillis(),
            nickname = event.nickname,
            avatarUrl = event.avatarUrl,
            callType = event.mediaType
        )

    private fun launchIncomingCallActivity(event: IncomingCallEvent) {
        try {
            startActivity(buildIncomingCallIntent(event))
            Log.i(TAG, "IncomingCallActivity launched directly from service")
        } catch (e: Exception) {
            Log.e(TAG, "Direct startActivity blocked — relying on full-screen notification: ${e.message}")
        }
    }

    private fun showIncomingCallNotification(event: IncomingCallEvent) {
        postIncomingCallNotification(this, event)
    }

    private fun dismissIncomingCallNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
    }
}
