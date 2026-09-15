package com.example.tv_caller_app.ui.util

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.widget.DatePicker
import android.widget.FrameLayout
import android.widget.TextView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.data.datasource.AppointmentRescheduleProposal
import com.example.tv_caller_app.domain.Appointment
import com.example.tv_caller_app.ui.views.VikomTimeWheelPicker
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Shows the invitation gate reminder using ViKom's own card and buttons. */
fun showInvitationRequiredMessage(context: Context) {
    showVikomMessage(
        context,
        context.getString(R.string.appointment_invitation_required_title),
        context.getString(R.string.appointment_invitation_required_message)
    )
}

/** A small reusable in-app message; this deliberately avoids system alert UI. */
fun showVikomMessage(context: Context, title: String, message: String) {
    val dialog = styledDialog(context, R.layout.dialog_vikom_message)
    dialog.findViewById<TextView>(R.id.txt_vikom_dialog_title).text = title
    dialog.findViewById<TextView>(R.id.txt_vikom_dialog_message).text = message
    dialog.findViewById<TextView>(R.id.btn_vikom_dialog_ok).setOnClickListener { dialog.dismiss() }
    showStyled(dialog)
}

/**
 * Accessible two-step decline flow shared by the home invitation and the
 * legacy notification detail screen. Every surface uses the ViKom visual
 * language, including ViKom's own 24-hour scrolling time wheel.
 */
fun showDeclineOptions(
    context: Context,
    appointment: Appointment,
    onDecline: (AppointmentRescheduleProposal?) -> Unit
) {
    val dialog = styledDialog(context, R.layout.dialog_appointment_decline)
    dialog.findViewById<TextView>(R.id.btn_decline_suggest).setOnClickListener {
        dialog.dismiss()
        showProposalDialog(context, appointment, onDecline)
    }
    dialog.findViewById<TextView>(R.id.btn_decline_without_suggestion).setOnClickListener {
        dialog.dismiss()
        onDecline(null)
    }
    dialog.findViewById<TextView>(R.id.btn_decline_cancel).setOnClickListener { dialog.dismiss() }
    showStyled(dialog)
}

private fun showProposalDialog(
    context: Context,
    appointment: Appointment,
    onDecline: (AppointmentRescheduleProposal?) -> Unit
) {
    var selectedDate = appointment.localDateOrTomorrow().plusDays(1)
    var selectedStart = appointment.localStartOrDefault()
    var selectedEnd = appointment.localEndOrDefault(selectedStart)
    if (!selectedEnd.isAfter(selectedStart)) selectedEnd = selectedStart.plusHours(1)

    val dialog = styledDialog(context, R.layout.dialog_appointment_proposal)
    val dateView = dialog.findViewById<TextView>(R.id.btn_proposal_date)
    val startView = dialog.findViewById<TextView>(R.id.btn_proposal_start)
    val endView = dialog.findViewById<TextView>(R.id.btn_proposal_end)

    fun updateLabels() {
        dateView.text = context.getString(
            R.string.appointment_proposal_date_value,
            selectedDate.format(DATE_FORMAT)
        )
        startView.text = context.getString(
            R.string.appointment_proposal_start_value,
            selectedStart.format(TIME_FORMAT)
        )
        endView.text = context.getString(
            R.string.appointment_proposal_end_value,
            selectedEnd.format(TIME_FORMAT)
        )
    }
    updateLabels()

    dateView.setOnClickListener {
        showDatePicker(context, selectedDate) {
            selectedDate = it
            updateLabels()
        }
    }
    startView.setOnClickListener {
        showTimePicker(context, R.string.appointment_choose_start_time, selectedStart) {
            selectedStart = it
            if (!selectedEnd.isAfter(selectedStart)) selectedEnd = selectedStart.plusHours(1)
            updateLabels()
        }
    }
    endView.setOnClickListener {
        showTimePicker(context, R.string.appointment_choose_end_time, selectedEnd) {
            selectedEnd = it
            updateLabels()
        }
    }
    dialog.findViewById<TextView>(R.id.btn_proposal_cancel).setOnClickListener { dialog.dismiss() }
    dialog.findViewById<TextView>(R.id.btn_proposal_send).setOnClickListener {
        val proposedStart = LocalDateTime.of(selectedDate, selectedStart)
        if (!proposedStart.isAfter(LocalDateTime.now()) || !selectedEnd.isAfter(selectedStart)) {
            showVikomMessage(
                context,
                context.getString(R.string.appointment_proposal_invalid_title),
                context.getString(R.string.appointment_proposal_invalid)
            )
            return@setOnClickListener
        }
        onDecline(
            AppointmentRescheduleProposal(
                date = selectedDate.toString(),
                startTime = selectedStart.format(TIME_FORMAT),
                endTime = selectedEnd.format(TIME_FORMAT)
            )
        )
        dialog.dismiss()
    }
    showStyled(dialog)
}

private fun showDatePicker(context: Context, current: LocalDate, onChoose: (LocalDate) -> Unit) {
    val dialog = styledDialog(context, R.layout.dialog_vikom_picker)
    dialog.findViewById<TextView>(R.id.txt_picker_title)
        .setText(R.string.appointment_choose_date)
    val picker = DatePicker(context).apply {
        minDate = System.currentTimeMillis()
        updateDate(current.year, current.monthValue - 1, current.dayOfMonth)
    }
    dialog.findViewById<FrameLayout>(R.id.picker_container).addView(picker)
    dialog.findViewById<TextView>(R.id.btn_picker_cancel).setOnClickListener { dialog.dismiss() }
    dialog.findViewById<TextView>(R.id.btn_picker_choose).setOnClickListener {
        onChoose(LocalDate.of(picker.year, picker.month + 1, picker.dayOfMonth))
        dialog.dismiss()
    }
    showStyled(dialog)
}

private fun showTimePicker(
    context: Context,
    titleRes: Int,
    current: LocalTime,
    onChoose: (LocalTime) -> Unit
) {
    val dialog = styledDialog(context, R.layout.dialog_vikom_picker)
    dialog.findViewById<TextView>(R.id.txt_picker_title).setText(titleRes)
    val picker = VikomTimeWheelPicker(context).apply { setTime(current) }
    dialog.findViewById<FrameLayout>(R.id.picker_container).addView(picker)
    dialog.findViewById<TextView>(R.id.btn_picker_cancel).setOnClickListener { dialog.dismiss() }
    dialog.findViewById<TextView>(R.id.btn_picker_choose).setOnClickListener {
        onChoose(picker.selectedTime())
        dialog.dismiss()
    }
    showStyled(dialog)
}

private fun styledDialog(context: Context, layoutRes: Int): Dialog = Dialog(context).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    setContentView(layoutRes)
    setCanceledOnTouchOutside(false)
    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
}

private fun showStyled(dialog: Dialog) {
    dialog.show()
    val context = dialog.context
    val availableWidth = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
    val preferredWidth = context.resources.getDimensionPixelSize(R.dimen.dialog_width)
    dialog.window?.setLayout(
        preferredWidth.coerceAtMost(availableWidth),
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}

private fun Appointment.localDateOrTomorrow(): LocalDate =
    runCatching { date?.let(LocalDate::parse) }.getOrNull()
        ?.takeIf { it.isAfter(LocalDate.now()) }
        ?: LocalDate.now().plusDays(1)

private fun Appointment.localStartOrDefault(): LocalTime =
    runCatching { startTime?.let(LocalTime::parse) }.getOrNull() ?: LocalTime.of(9, 0)

private fun Appointment.localEndOrDefault(start: LocalTime): LocalTime =
    runCatching { endTime?.let(LocalTime::parse) }.getOrNull() ?: start.plusHours(1)
