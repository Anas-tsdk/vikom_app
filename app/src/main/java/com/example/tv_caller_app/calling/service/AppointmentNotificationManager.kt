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
import com.example.tv_caller_app.domain.AppointmentEvent
import com.example.tv_caller_app.ui.activities.MainActivity

object AppointmentNotificationManager {
    private const val CHANNEL_ID = "appointment_updates"
    private const val BASE_NOTIFICATION_ID = 40_000

    fun show(context: Context, event: AppointmentEvent) {
        createChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId(event.appointmentId),
            MainActivity.createIntent(
                context,
                if (isInvitation(event)) null else MainActivity.DESTINATION_APPOINTMENTS
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isInvitation = isInvitation(event)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_calendar)
            .setContentTitle(
                context.getString(
                    if (isInvitation) R.string.appointment_invitation_title
                    else R.string.appointment_update_title
                )
            )
            .setContentText(
                if (event.date.isBlank() || event.startTime.isBlank()) {
                    context.getString(R.string.appointment_notification_open_details)
                } else {
                    context.getString(
                        R.string.appointment_notification_text,
                        event.date,
                        event.startTime
                    )
                }
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(event.appointmentId), notification)
    }

    fun cancel(context: Context, appointmentId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId(appointmentId))
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.appointment_notification_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.appointment_notification_channel_description)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
        )
    }

    private fun notificationId(appointmentId: Int): Int =
        BASE_NOTIFICATION_ID + appointmentId.coerceAtLeast(0) % 20_000

    private fun isInvitation(event: AppointmentEvent): Boolean =
        event.patientResponseStatus.equals("Pending", ignoreCase = true) &&
            event.status.equals("Booked", ignoreCase = true)
}
