package com.example.tv_caller_app.ui.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.tv_caller_app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

object AppointmentStatusUi {
    @StringRes
    fun labelRes(status: String?): Int = when (status) {
        "Booked" -> R.string.appointment_status_booked
        "InProgress" -> R.string.appointment_status_inprogress
        "Completed" -> R.string.appointment_status_completed
        "NotCompleted" -> R.string.appointment_status_notcompleted
        "Cancelled" -> R.string.appointment_status_cancelled
        else -> R.string.appointment_title_default
    }

    @DrawableRes
    fun chipRes(status: String?): Int = when (status) {
        "Booked" -> R.drawable.status_chip_booked
        "Completed" -> R.drawable.status_chip_completed
        "Cancelled" -> R.drawable.status_chip_cancelled
        else -> R.drawable.status_chip_neutral
    }

    @StringRes
    fun responseLabelRes(response: String?): Int = when {
        response.equals("Accepted", ignoreCase = true) -> R.string.appointment_response_accepted
        response.equals("Declined", ignoreCase = true) -> R.string.appointment_response_declined
        else -> R.string.appointment_response_pending
    }

    @DrawableRes
    fun responseChipRes(response: String?): Int = when {
        response.equals("Accepted", ignoreCase = true) -> R.drawable.status_chip_completed
        response.equals("Declined", ignoreCase = true) -> R.drawable.status_chip_cancelled
        else -> R.drawable.status_chip_booked
    }

    @StringRes
    fun actionLabelRes(action: String?): Int = when (action?.lowercase()) {
        "created" -> R.string.appointment_action_created
        "updated" -> R.string.appointment_action_updated
        "cancelled" -> R.string.appointment_action_cancelled
        "accepted" -> R.string.appointment_action_accepted
        "declined" -> R.string.appointment_action_declined
        else -> R.string.appointment_title_default
    }

    fun formatLongDate(isoDate: String?): String {
        if (isoDate.isNullOrBlank()) return ""
        return runCatching {
            LocalDate.parse(isoDate)
                .format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
        }.getOrDefault(isoDate)
    }
}

