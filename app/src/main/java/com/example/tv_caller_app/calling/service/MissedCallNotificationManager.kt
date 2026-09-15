package com.example.tv_caller_app.calling.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.activities.MainActivity

object MissedCallNotificationManager {
    private const val CHANNEL_ID = "missed_calls"
    private const val BASE_ID = 70_000

    fun show(context: Context, callId: String, callerName: String, mediaType: String) {
        createChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val displayName = callerName.ifBlank { context.getString(R.string.unknown_contact) }
        val text = context.getString(
            if (mediaType.equals("video", ignoreCase = true)) {
                R.string.missed_video_call_message
            } else {
                R.string.missed_audio_call_message
            },
            displayName
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            MainActivity.createIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(context.getString(R.string.missed_call_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        NotificationManagerCompat.from(context).notify(BASE_ID + (callId.hashCode() and 0x3fff), notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.missed_call_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.missed_call_notification_channel_description)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }
}

