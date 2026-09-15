package com.example.tv_caller_app.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.tv_caller_app.R
import com.example.tv_caller_app.data.datasource.AppointmentResponse
import com.example.tv_caller_app.domain.Appointment
import com.example.tv_caller_app.domain.canRespond
import com.example.tv_caller_app.domain.isUpcoming
import com.example.tv_caller_app.domain.localDate
import com.example.tv_caller_app.domain.startDateTime
import com.example.tv_caller_app.ui.util.AppointmentStatusUi
import com.example.tv_caller_app.ui.util.TaskTranslation
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed class DayGroupedItem {
    data class SectionHeader(@StringRes val titleRes: Int) : DayGroupedItem()
    data class Visit(
        val appointment: Appointment,
        val startsDay: Boolean,
        val date: LocalDate?,
        val isPast: Boolean
    ) : DayGroupedItem()
}

class DayGroupedAppointmentAdapter(
    private val items: List<DayGroupedItem>,
    private val responseInProgressId: Int? = null,
    private val onRespond: (Appointment, AppointmentResponse) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val expanded = mutableSetOf<Int>()

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_VISIT = 1

        fun build(appointments: List<Appointment>, now: LocalDateTime): List<DayGroupedItem> {
            val (upcoming, past) = appointments.partition { appointment ->
                val hasActiveStatus =
                    appointment.status.equals("Booked", ignoreCase = true) ||
                        appointment.status.equals("InProgress", ignoreCase = true)
                val wasDeclined = appointment.patientResponseStatus.equals(
                    "Declined",
                    ignoreCase = true
                )
                hasActiveStatus && !wasDeclined && appointment.isUpcoming(now)
            }
            return buildList {
                addAll(flattenByDay(upcoming.sortedBy { it.startDateTime() ?: LocalDateTime.MAX }, false))
                if (past.isNotEmpty()) {
                    add(DayGroupedItem.SectionHeader(R.string.appointments_section_past))
                    addAll(flattenByDay(past.sortedByDescending { it.startDateTime() ?: LocalDateTime.MIN }, true))
                }
            }
        }

        private fun flattenByDay(sorted: List<Appointment>, isPast: Boolean): List<DayGroupedItem> {
            var previousDate: LocalDate? = null
            var first = true
            return sorted.map { appointment ->
                val date = appointment.localDate()
                val startsDay = first || date != previousDate
                first = false
                previousDate = date
                DayGroupedItem.Visit(appointment, startsDay, date, isPast)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is DayGroupedItem.SectionHeader -> VIEW_TYPE_HEADER
        is DayGroupedItem.Visit -> VIEW_TYPE_VISIT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_appointment_section_header, parent, false))
        } else {
            VisitViewHolder(inflater.inflate(R.layout.item_appointment_day, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DayGroupedItem.SectionHeader -> (holder as HeaderViewHolder).bind(item)
            is DayGroupedItem.Visit -> (holder as VisitViewHolder).bind(item, expanded.contains(position))
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.section_title)
        fun bind(item: DayGroupedItem.SectionHeader) = title.setText(item.titleRes)
    }

    private inner class VisitViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val dayLabel: TextView = view.findViewById(R.id.txt_day_label)
        private val dayNumber: TextView = view.findViewById(R.id.txt_day_number)
        private val dayMonth: TextView = view.findViewById(R.id.txt_day_month)
        private val dayDot: View = view.findViewById(R.id.day_dot)
        private val card: View = view.findViewById(R.id.visit_card)
        private val time: TextView = view.findViewById(R.id.txt_visit_time)
        private val personnel: TextView = view.findViewById(R.id.txt_visit_personnel)
        private val tasks: TextView = view.findViewById(R.id.txt_visit_tasks)
        private val status: TextView = view.findViewById(R.id.txt_visit_status)
        private val responseStatus: TextView = view.findViewById(R.id.txt_response_status)
        private val responseActions: View = view.findViewById(R.id.response_actions)
        private val accept: TextView = view.findViewById(R.id.btn_accept_appointment)
        private val decline: TextView = view.findViewById(R.id.btn_decline_appointment)

        fun bind(item: DayGroupedItem.Visit, isExpanded: Boolean) {
            val context = itemView.context
            val appointment = item.appointment
            bindAnchor(item)

            time.text = context.getString(
                R.string.appointment_time_format,
                appointment.startTime.orEmpty(),
                appointment.endTime.orEmpty()
            )
            personnel.text = appointment.personnelName
                ?.takeIf(String::isNotBlank)
                ?.let { context.getString(R.string.appointment_personnel_format, it) }
                .orEmpty()
            personnel.visibility = if (personnel.text.isBlank()) View.GONE else View.VISIBLE

            tasks.text = TaskTranslation.translateList(context, appointment.tasks.orEmpty())
            tasks.visibility = if (tasks.text.isBlank()) View.GONE else View.VISIBLE
            tasks.maxLines = if (isExpanded) Int.MAX_VALUE else 2

            status.setText(AppointmentStatusUi.labelRes(appointment.status))
            status.setBackgroundResource(AppointmentStatusUi.chipRes(appointment.status))
            responseStatus.setText(AppointmentStatusUi.responseLabelRes(appointment.patientResponseStatus))
            responseStatus.setBackgroundResource(AppointmentStatusUi.responseChipRes(appointment.patientResponseStatus))

            val canRespond = appointment.canRespond()
            val busy = responseInProgressId == appointment.id
            responseActions.visibility = if (canRespond) View.VISIBLE else View.GONE
            accept.isEnabled = !busy
            decline.isEnabled = !busy
            accept.alpha = if (busy) 0.55f else 1f
            decline.alpha = if (busy) 0.55f else 1f
            accept.setOnClickListener { if (!busy) onRespond(appointment, AppointmentResponse.ACCEPTED) }
            decline.setOnClickListener { if (!busy) onRespond(appointment, AppointmentResponse.DECLINED) }

            card.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                if (!expanded.add(position)) expanded.remove(position)
                notifyItemChanged(position)
            }
        }

        private fun bindAnchor(item: DayGroupedItem.Visit) {
            val date = item.date
            if (!item.startsDay || date == null) {
                listOf(dayLabel, dayNumber, dayMonth, dayDot).forEach { it.visibility = View.INVISIBLE }
                return
            }

            listOf(dayLabel, dayNumber, dayMonth, dayDot).forEach { it.visibility = View.VISIBLE }
            val today = LocalDate.now()
            dayLabel.text = when (date) {
                today -> itemView.context.getString(R.string.day_anchor_today)
                today.plusDays(1) -> itemView.context.getString(R.string.day_anchor_tomorrow)
                else -> date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
            }
            dayNumber.text = java.text.NumberFormat.getIntegerInstance(Locale.getDefault())
                .format(date.dayOfMonth)
            dayMonth.text = date.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()))

            val tint = ContextCompat.getColor(itemView.context, dayTint(date, today, item.isPast))
            dayLabel.setTextColor(tint)
            dayDot.background?.mutate()?.let { DrawableCompat.setTint(it, tint) }
        }

        @ColorRes
        private fun dayTint(date: LocalDate, today: LocalDate, isPast: Boolean): Int = when {
            isPast -> R.color.vk_gray_500
            date == today -> R.color.vk_success
            date == today.plusDays(1) -> R.color.vk_primary
            else -> R.color.vk_gray_500
        }
    }
}
