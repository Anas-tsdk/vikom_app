package com.example.tv_caller_app.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.example.tv_caller_app.R
import com.example.tv_caller_app.calling.service.AppointmentNotificationManager
import com.example.tv_caller_app.data.datasource.AppointmentResponse
import com.example.tv_caller_app.domain.Appointment
import com.example.tv_caller_app.domain.AppointmentEvent
import com.example.tv_caller_app.domain.canRespond
import com.example.tv_caller_app.settings.SettingsManager
import com.example.tv_caller_app.ui.util.AppointmentStatusUi
import com.example.tv_caller_app.ui.util.TaskTranslation
import com.example.tv_caller_app.ui.util.showDeclineOptions
import com.example.tv_caller_app.ui.viewmodel.AppointmentsUiState
import com.example.tv_caller_app.ui.viewmodel.AppointmentsViewModel
import com.example.tv_caller_app.ui.viewmodel.AppointmentsViewModelFactory
import java.util.Locale

class AppointmentActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_APPOINTMENT_ID = "appointment_id"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_DATE = "date"
        private const val EXTRA_START_TIME = "start_time"
        private const val EXTRA_END_TIME = "end_time"
        private const val EXTRA_PERSONNEL_NAME = "personnel_name"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_PATIENT_RESPONSE = "patient_response"
        private const val EXTRA_SHORT_MESSAGE = "short_message"
        private const val STATE_AWAITING_DETAILS = "awaiting_details"

        fun createIntent(context: Context, event: AppointmentEvent): Intent =
            Intent(context, AppointmentActivity::class.java).apply {
                putExtra(EXTRA_APPOINTMENT_ID, event.appointmentId)
                putExtra(EXTRA_ACTION, event.action)
                putExtra(EXTRA_DATE, event.date)
                putExtra(EXTRA_START_TIME, event.startTime)
                putExtra(EXTRA_END_TIME, event.endTime)
                putExtra(EXTRA_PERSONNEL_NAME, event.personnelName)
                putExtra(EXTRA_STATUS, event.status)
                putExtra(EXTRA_PATIENT_RESPONSE, event.patientResponseStatus)
                putExtra(EXTRA_SHORT_MESSAGE, event.shortMessage)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }

    private lateinit var viewModel: AppointmentsViewModel
    private lateinit var actions: View
    private lateinit var prompt: View
    private lateinit var progress: View
    private lateinit var accept: TextView
    private lateinit var decline: TextView
    private var appointmentId = 0
    private var currentCanRespond = false
    private var responseBusy = false
    private var awaitingDetails = false
    private var currentAction = ""
    private var currentMessage = ""
    private var currentAppointment = Appointment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLocale()
        setContentView(R.layout.activity_appointment)
        viewModel = ViewModelProvider(
            this,
            AppointmentsViewModelFactory(application)
        )[AppointmentsViewModel::class.java]
        bindViews()
        showIntent(intent)
        if (savedInstanceState?.getBoolean(STATE_AWAITING_DETAILS) == true) {
            awaitingDetails = true
            currentCanRespond = false
            updateResponseControls()
        }
        observeViewModel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_AWAITING_DETAILS, awaitingDetails)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showIntent(intent)
    }

    private fun bindViews() {
        actions = findViewById(R.id.appointment_response_actions)
        prompt = findViewById(R.id.txt_appointment_prompt)
        progress = findViewById(R.id.txt_appointment_progress)
        accept = findViewById(R.id.btn_appointment_accept)
        decline = findViewById(R.id.btn_appointment_decline)

        accept.setOnClickListener { respond(AppointmentResponse.ACCEPTED) }
        decline.setOnClickListener {
            showDeclineOptions(this, currentAppointment) { proposal ->
                respond(AppointmentResponse.DECLINED, proposal)
            }
        }

        findViewById<View>(R.id.btn_appointment_see_all).setOnClickListener {
            startActivity(MainActivity.createIntent(this, MainActivity.DESTINATION_APPOINTMENTS))
            finish()
        }
        findViewById<View>(R.id.btn_appointment_close).setOnClickListener {
            startActivity(MainActivity.createIntent(this))
            finish()
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is AppointmentsUiState.Content -> {
                    val appointment = state.appointments.firstOrNull { it.id == appointmentId }
                    if (appointment != null) {
                        awaitingDetails = false
                        renderAppointment(appointment, detailsAvailable = true)
                    } else if (awaitingDetails) {
                        showDetailsState(R.string.appointments_empty)
                    }
                }
                is AppointmentsUiState.Loading -> {
                    if (awaitingDetails) showDetailsState(R.string.appointments_loading)
                }
                is AppointmentsUiState.NotLinked -> {
                    if (awaitingDetails) showDetailsState(R.string.appointments_not_linked)
                }
                is AppointmentsUiState.SessionExpired -> {
                    if (awaitingDetails) showDetailsState(R.string.appointments_session_expired)
                }
                is AppointmentsUiState.NotConfigured -> {
                    if (awaitingDetails) showDetailsState(R.string.appointments_not_configured)
                }
                is AppointmentsUiState.Empty -> {
                    if (awaitingDetails) showDetailsState(R.string.appointments_empty)
                }
                is AppointmentsUiState.Error -> {
                    if (awaitingDetails) showDetailsState(R.string.appointments_error)
                }
            }
        }
        viewModel.responseInProgress.observe(this) { activeId ->
            responseBusy = activeId == appointmentId
            updateResponseControls()
        }
        viewModel.responseCompletedId.observe(this) { completedId ->
            if (completedId == appointmentId) {
                AppointmentNotificationManager.cancel(this, appointmentId)
                viewModel.clearResponseCompleted()
                finish()
            }
        }
        viewModel.responseError.observe(this) { error ->
            if (!error.isNullOrBlank()) {
                awaitingDetails = true
                showDetailsState(R.string.appointments_loading)
                Toast.makeText(this, getString(R.string.appointment_response_error, error), Toast.LENGTH_LONG).show()
                viewModel.clearResponseError()
            }
        }
    }

    private fun showIntent(source: Intent) {
        appointmentId = source.getIntExtra(EXTRA_APPOINTMENT_ID, 0)
        currentAction = source.getStringExtra(EXTRA_ACTION).orEmpty()
        currentMessage = source.getStringExtra(EXTRA_SHORT_MESSAGE).orEmpty()
        responseBusy = viewModel.responseInProgress.value == appointmentId

        val date = source.getStringExtra(EXTRA_DATE).orEmpty()
        val startTime = source.getStringExtra(EXTRA_START_TIME).orEmpty()
        val endTime = source.getStringExtra(EXTRA_END_TIME).orEmpty()
        val status = source.getStringExtra(EXTRA_STATUS).orEmpty()
        val response = source.getStringExtra(EXTRA_PATIENT_RESPONSE).orEmpty()
        val appointment = Appointment(
            id = appointmentId,
            personnelName = source.getStringExtra(EXTRA_PERSONNEL_NAME),
            date = date,
            startTime = startTime,
            endTime = endTime,
            status = status,
            patientResponseStatus = response
        )
        val hasDetails = date.isNotBlank() &&
            startTime.isNotBlank() &&
            endTime.isNotBlank() &&
            status.isNotBlank() &&
            response.isNotBlank()
        awaitingDetails = appointmentId > 0 && !hasDetails
        renderAppointment(appointment, detailsAvailable = hasDetails)
        if (awaitingDetails) viewModel.load(forceRefresh = true)
    }

    private fun renderAppointment(appointment: Appointment, detailsAvailable: Boolean) {
        currentAppointment = appointment
        findViewById<TextView>(R.id.txt_appointment_action)
            .setText(AppointmentStatusUi.actionLabelRes(currentAction))
        findViewById<TextView>(R.id.txt_appointment_status).apply {
            visibility = if (detailsAvailable) View.VISIBLE else View.GONE
            if (detailsAvailable) {
                setText(AppointmentStatusUi.labelRes(appointment.status))
                setBackgroundResource(AppointmentStatusUi.chipRes(appointment.status))
            }
        }
        findViewById<TextView>(R.id.txt_appointment_response).apply {
            visibility = if (detailsAvailable) View.VISIBLE else View.GONE
            if (detailsAvailable) {
                setText(AppointmentStatusUi.responseLabelRes(appointment.patientResponseStatus))
                setBackgroundResource(AppointmentStatusUi.responseChipRes(appointment.patientResponseStatus))
            }
        }
        findViewById<TextView>(R.id.txt_appointment_date).apply {
            text = AppointmentStatusUi.formatLongDate(appointment.date)
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.txt_appointment_time).apply {
            val start = appointment.startTime.orEmpty()
            val end = appointment.endTime.orEmpty()
            text = when {
                start.isBlank() -> ""
                end.isBlank() -> start
                else -> getString(R.string.appointment_time_format, start, end)
            }
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.txt_appointment_personnel).apply {
            text = appointment.personnelName
                ?.takeIf(String::isNotBlank)
                ?.let { getString(R.string.appointment_personnel_format, it) }
                .orEmpty()
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.txt_appointment_message).apply {
            text = TaskTranslation.translateList(
                this@AppointmentActivity,
                currentMessage.ifBlank { appointment.tasks.orEmpty() }
            )
            visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }

        currentCanRespond = detailsAvailable && appointment.canRespond()
        updateResponseControls()
        if (currentCanRespond && !responseBusy) {
            accept.requestFocus()
        } else {
            findViewById<View>(R.id.btn_appointment_close).requestFocus()
        }
    }

    private fun updateResponseControls() {
        progress.visibility = if (responseBusy) View.VISIBLE else View.GONE
        val showActions = currentCanRespond && !responseBusy
        actions.visibility = if (showActions) View.VISIBLE else View.GONE
        prompt.visibility = if (showActions) View.VISIBLE else View.GONE
    }

    private fun showDetailsState(messageRes: Int) {
        currentCanRespond = false
        updateResponseControls()
        findViewById<TextView>(R.id.txt_appointment_message).apply {
            setText(messageRes)
            visibility = View.VISIBLE
        }
    }

    private fun respond(
        response: AppointmentResponse,
        proposal: com.example.tv_caller_app.data.datasource.AppointmentRescheduleProposal? = null
    ) {
        if (appointmentId <= 0) return
        viewModel.respond(appointmentId, response, proposal)
    }

    private fun applyLocale() {
        val locale = Locale.forLanguageTag(SettingsManager.getInstance(this).language)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
