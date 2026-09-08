package com.example.tv_caller_app.calling.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.activities.InCallActivity
import java.util.concurrent.atomic.AtomicInteger

class CallService : Service() {

    companion object {
        private const val TAG = "CallService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "call_service_channel"
        private const val CHANNEL_NAME = "Active Calls"

        private const val ACTION_START = "com.example.tv_caller_app.ACTION_START_CALL"
        private const val ACTION_STOP = "com.example.tv_caller_app.ACTION_STOP_CALL"
        private const val ACTION_MUTE = "com.example.tv_caller_app.ACTION_MUTE"
        private const val ACTION_SPEAKER = "com.example.tv_caller_app.ACTION_SPEAKER"
        private const val ACTION_HANG_UP = "com.example.tv_caller_app.ACTION_HANG_UP"
        private const val ACTION_UPDATE_TIMER = "com.example.tv_caller_app.ACTION_UPDATE_TIMER"

        private const val EXTRA_CONTACT_NAME = "contact_name"
        private const val EXTRA_IS_INCOMING = "is_incoming"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_IS_MUTED = "is_muted"
        private const val EXTRA_IS_SPEAKER_ON = "is_speaker_on"

        private val requestCodeGenerator = AtomicInteger(2000)

        fun startService(context: Context, contactName: String, isIncoming: Boolean) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.d(TAG,"Service start requested for: $contactName")
        }

        fun updateCallDuration(
            context: Context,
            durationSeconds: Long,
            isMuted: Boolean = false,
            isSpeakerOn: Boolean = false
        ) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_UPDATE_TIMER
                putExtra(EXTRA_DURATION, durationSeconds)
                putExtra(EXTRA_IS_MUTED, isMuted)
                putExtra(EXTRA_IS_SPEAKER_ON, isSpeakerOn)
            }

            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_STOP
            }

            context.startService(intent)
            Log.d(TAG,"Service stop requested")
        }
    }

    private var contactName: String = ""
    private var isIncoming: Boolean = false
    private var callDuration: Long = 0
    private var isMuted: Boolean = false
    private var isSpeakerOn: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "CallService created")

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand - action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: getString(R.string.unknown_contact)
                isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
                callDuration = 0

                Log.i(TAG, "Starting foreground service for call with: $contactName")

                startForeground(NOTIFICATION_ID, buildNotification())
            }

            ACTION_UPDATE_TIMER -> {
                callDuration = intent.getLongExtra(EXTRA_DURATION, 0)
                isMuted = intent.getBooleanExtra(EXTRA_IS_MUTED, false)
                isSpeakerOn = intent.getBooleanExtra(EXTRA_IS_SPEAKER_ON, false)

                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            }

            ACTION_STOP -> {
                Log.i(TAG, "Stopping foreground service")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }

            ACTION_MUTE, ACTION_SPEAKER, ACTION_HANG_UP -> {
                Log.d(TAG, "Action received: ${intent.action}")
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "CallService destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_for_active_calls)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }

    private fun buildNotification(): Notification {
        val activityIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val activityPendingIntent = PendingIntent.getActivity(
            this,
            requestCodeGenerator.incrementAndGet(),
            activityIntent,
            pendingIntentFlags
        )

        val durationText = formatDuration(callDuration)
        val callTypeText = if (isIncoming) getString(R.string.incoming_call) else getString(R.string.outgoing_call)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.call_with, contactName))
            .setContentText(getString(R.string.notification_call_status, callTypeText, durationText))
            .setSmallIcon(R.drawable.ic_call)
            .setContentIntent(activityPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setSilent(true)

        val statusParts = mutableListOf<String>()
        if (isMuted) statusParts.add(getString(R.string.muted))
        if (isSpeakerOn) statusParts.add(getString(R.string.speaker_on))

        if (statusParts.isNotEmpty()) {
            val statusText = statusParts.joinToString(" • ")
            builder.setSubText(statusText)
        }

        return builder.build()
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, secs)
    }
}
