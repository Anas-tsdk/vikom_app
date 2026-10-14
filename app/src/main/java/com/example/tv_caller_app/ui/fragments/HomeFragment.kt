package com.example.tv_caller_app.ui.fragments

import android.os.Bundle
import android.os.Build
import android.content.res.Configuration
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.calling.service.AppointmentNotificationManager
import com.example.tv_caller_app.data.datasource.AppointmentResponse
import com.example.tv_caller_app.data.repository.PatientProfileRepository
import com.example.tv_caller_app.domain.Appointment
import com.example.tv_caller_app.domain.canRespond
import com.example.tv_caller_app.domain.isUpcoming
import com.example.tv_caller_app.domain.localDate
import com.example.tv_caller_app.domain.startDateTime
import com.example.tv_caller_app.ui.util.AppointmentStatusUi
import com.example.tv_caller_app.ui.util.TaskTranslation
import com.example.tv_caller_app.ui.util.showDeclineOptions
import com.example.tv_caller_app.ui.util.showVikomMessage
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.viewmodel.AppointmentsUiState
import com.example.tv_caller_app.ui.viewmodel.AppointmentsViewModel
import com.example.tv_caller_app.ui.viewmodel.AppointmentsViewModelFactory
import com.example.tv_caller_app.ui.viewmodel.QuickDialViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Simple patient-facing entry screen.
 *
 * Calling deliberately opens [QuickDialFragment], which keeps the established
 * audio/video call experience unchanged. The other cards lead to their focused
 * healthcare screens, and Back always returns here.
 */
class HomeFragment : Fragment() {

    private val appointmentsViewModel: AppointmentsViewModel by activityViewModels {
        AppointmentsViewModelFactory(requireActivity().application)
    }

    private val quickDialViewModel: QuickDialViewModel by activityViewModels()

    private lateinit var heroCard: View
    private lateinit var txtEyebrow: TextView
    private lateinit var txtTime: TextView
    private lateinit var txtWho: TextView
    private lateinit var txtStatus: TextView
    private lateinit var taskRow: LinearLayout
    private lateinit var txtHeroState: TextView
    private lateinit var invitationTitle: TextView
    private lateinit var invitationPanel: View
    private lateinit var invitationActions: View
    private lateinit var invitationProgress: View
    private lateinit var invitationAccept: TextView
    private lateinit var invitationDecline: TextView
    private lateinit var txtAppointmentsSub: TextView
    private lateinit var txtContactsSub: TextView
    private lateinit var txtDailyTasksSub: TextView
    private lateinit var appointmentsTile: View
    private lateinit var callTile: View
    private lateinit var dailyTasksTile: View

    private var cachedAppointments: List<Appointment>? = null
    private var currentInvitation: Appointment? = null
    private var appointmentClockJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_home))

        heroCard = view.findViewById(R.id.hero_card)
        txtEyebrow = view.findViewById(R.id.txt_hero_eyebrow)
        txtTime = view.findViewById(R.id.txt_hero_time)
        txtWho = view.findViewById(R.id.txt_hero_who)
        txtStatus = view.findViewById(R.id.txt_hero_status)
        taskRow = view.findViewById(R.id.hero_tasks)
        txtHeroState = view.findViewById(R.id.txt_hero_state)
        invitationTitle = view.findViewById(R.id.txt_invitation_title)
        invitationPanel = view.findViewById(R.id.invitation_panel)
        invitationActions = view.findViewById(R.id.invitation_response_actions)
        invitationProgress = view.findViewById(R.id.txt_invitation_progress)
        invitationAccept = view.findViewById(R.id.btn_home_invitation_accept)
        invitationDecline = view.findViewById(R.id.btn_home_invitation_decline)
        txtAppointmentsSub = view.findViewById(R.id.txt_tile_appointments_sub)
        txtContactsSub = view.findViewById(R.id.txt_tile_contacts_sub)
        txtDailyTasksSub = view.findViewById(R.id.txt_tile_care_team_sub)

        appointmentsTile = view.findViewById(R.id.tile_appointments)
        callTile = view.findViewById(R.id.tile_contacts)
        dailyTasksTile = view.findViewById(R.id.tile_care_team)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(heroCard, appointmentsTile, callTile, dailyTasksTile).forEach {
                it.defaultFocusHighlightEnabled = false
            }
        }

        heroCard.setOnClickListener { navigateOrRemind { open(AppointmentListFragment()) } }
        appointmentsTile.setOnClickListener {
            navigateOrRemind { open(AppointmentListFragment()) }
        }
        callTile.setOnClickListener {
            navigateOrRemind { open(QuickDialFragment()) }
        }
        dailyTasksTile.setOnClickListener {
            navigateOrRemind { open(AIFragment.forDailyTasks()) }
        }
        invitationAccept.setOnClickListener {
            currentInvitation?.let { appointmentsViewModel.respond(it.id, AppointmentResponse.ACCEPTED) }
        }
        invitationDecline.setOnClickListener {
            currentInvitation?.let { appointment ->
                showDeclineOptions(requireContext(), appointment) { proposal ->
                    appointmentsViewModel.respond(
                        appointment.id,
                        AppointmentResponse.DECLINED,
                        proposal
                    )
                }
            }
        }

        appointmentsViewModel.uiState.observe(viewLifecycleOwner, ::render)
        appointmentsViewModel.responseInProgress.observe(viewLifecycleOwner) {
            updateInvitationResponseState()
        }
        appointmentsViewModel.responseCompletedId.observe(viewLifecycleOwner) { completedId ->
            if (completedId != null) {
                AppointmentNotificationManager.cancel(requireContext(), completedId)
                appointmentsViewModel.clearResponseCompleted()
            }
        }
        appointmentsViewModel.responseError.observe(viewLifecycleOwner) { error ->
            if (!error.isNullOrBlank()) {
                showVikomMessage(
                    requireContext(),
                    getString(R.string.appointment_response_error_title),
                    getString(R.string.appointment_response_error, error)
                )
                appointmentsViewModel.clearResponseError()
            }
        }
        quickDialViewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            txtContactsSub.text = getString(R.string.home_contacts_count, contacts.size)
        }
        loadDailyTasksSummary()

        // Initial focus is useful for a TV remote, but on a touchscreen it makes
        // the first tap merely leave non-touch mode. Keep tablet/phone cards
        // immediately actionable with one tap.
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        if (isTv) {
            if (currentInvitation != null) invitationAccept.requestFocus() else heroCard.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        startAppointmentClock()
        appointmentsViewModel.load(forceRefresh = true)
        // Warm the calling screen while the patient is reading the home screen.
        // Opening it then renders cached contacts immediately.
        quickDialViewModel.refreshQuickDialContacts(forceRefresh = false)
    }

    override fun onPause() {
        stopAppointmentClock()
        super.onPause()
    }

    override fun onDestroyView() {
        stopAppointmentClock()
        cachedAppointments = null
        super.onDestroyView()
    }

    private fun open(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun navigateOrRemind(action: () -> Unit) {
        if (currentInvitation != null) {
            (activity as? MainActivity)?.showInvitationReminder()
        } else {
            action()
        }
    }

    private fun loadDailyTasksSummary() {
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as TVCallerApplication
            val profile = runCatching {
                PatientProfileRepository.getInstance(
                    SessionManager.getInstance(requireContext()),
                    app.getAuthRepository()
                ).getMyProfile(forceRefresh = false)
            }.getOrNull()

            val count = profile?.dailyTasks.orEmpty()
                .split(Regex("[\\r\\n;]+"))
                .map(String::trim)
                .count(String::isNotBlank)
            txtDailyTasksSub.text = if (profile == null) {
                getString(R.string.ai_data_unavailable)
            } else if (count > 0) {
                resources.getQuantityString(R.plurals.ai_daily_task_count, count, count)
            } else {
                getString(R.string.ai_no_daily_tasks_short)
            }
        }
    }

    private fun render(state: AppointmentsUiState) {
        when (state) {
            is AppointmentsUiState.Loading -> {
                if (currentInvitation == null) resetAppointments(R.string.appointments_loading)
            }
            is AppointmentsUiState.Empty -> resetAppointmentsAndUnlock(R.string.appointments_empty)
            is AppointmentsUiState.NotLinked -> resetAppointmentsAndUnlock(R.string.appointments_not_linked)
            is AppointmentsUiState.NotConfigured -> resetAppointmentsAndUnlock(R.string.appointments_not_configured)
            is AppointmentsUiState.SessionExpired -> resetAppointmentsAndUnlock(R.string.appointments_session_expired)
            is AppointmentsUiState.Error -> {
                if (currentInvitation == null) resetAppointments(R.string.appointments_error)
            }
            is AppointmentsUiState.Content -> {
                cachedAppointments = state.appointments
                renderAppointments(state.appointments)
            }
        }
    }

    private fun resetAppointments(messageRes: Int) {
        cachedAppointments = null
        txtAppointmentsSub.text = getString(R.string.home_appointments_count, 0)
        showInvitationMode(false)
        heroMessage(messageRes)
    }

    private fun resetAppointmentsAndUnlock(messageRes: Int) {
        currentInvitation = null
        (activity as? MainActivity)?.setInvitationGate(false)
        resetAppointments(messageRes)
    }

    /**
     * Appointment eligibility changes as the clock crosses an end time, even if
     * no database event arrives. Re-render on wall-clock minute boundaries while
     * this screen is visible so both the hero and count stay current.
     */
    private fun startAppointmentClock() {
        stopAppointmentClock()
        appointmentClockJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val millisIntoMinute = System.currentTimeMillis() % MINUTE_MILLIS
                delay((MINUTE_MILLIS - millisIntoMinute).coerceAtLeast(1L))
                cachedAppointments?.let(::renderAppointments)
            }
        }
    }

    private fun stopAppointmentClock() {
        appointmentClockJob?.cancel()
        appointmentClockJob = null
    }

    private fun renderAppointments(appointments: List<Appointment>) {
        val now = LocalDateTime.now()
        val pendingInvitation = appointments
            .asSequence()
            .filter { it.canRespond(now) }
            .minByOrNull { it.startDateTime() ?: LocalDateTime.MAX }
        val upcoming = appointments
            .filter {
                (it.status.equals("Booked", ignoreCase = true) &&
                    !it.patientResponseStatus.equals("Declined", ignoreCase = true)) ||
                    it.status.equals("InProgress", ignoreCase = true)
            }
            .filter { it.isUpcoming(now) }
            .sortedBy { it.startDateTime() ?: LocalDateTime.MAX }

        txtAppointmentsSub.text = getString(R.string.home_appointments_count, upcoming.size)

        if (pendingInvitation != null) {
            currentInvitation = pendingInvitation
            (activity as? MainActivity)?.setInvitationGate(true)
            renderInvitation(pendingInvitation)
            return
        }

        currentInvitation = null
        (activity as? MainActivity)?.setInvitationGate(false)
        showInvitationMode(false)

        val next = upcoming.firstOrNull()
        if (next == null) {
            heroMessage(R.string.appointments_none_upcoming)
            return
        }

        showHeroFields(true)
        txtHeroState.visibility = View.GONE
        txtEyebrow.text = eyebrowFor(next.localDate())
        txtTime.text = next.startTime.orEmpty()
        txtWho.text = next.personnelName
            ?.takeIf { it.isNotBlank() }
            ?.let { getString(R.string.appointment_personnel_format, it) }
            .orEmpty()
        if (next.status.equals("Booked", ignoreCase = true)) {
            txtStatus.setText(AppointmentStatusUi.responseLabelRes(next.patientResponseStatus))
            txtStatus.setBackgroundResource(
                AppointmentStatusUi.responseChipRes(next.patientResponseStatus)
            )
        } else {
            txtStatus.setText(AppointmentStatusUi.labelRes(next.status))
            txtStatus.setBackgroundResource(AppointmentStatusUi.chipRes(next.status))
        }
        bindTasks(next.tasks)
    }

    private fun renderInvitation(appointment: Appointment) {
        showInvitationMode(true)
        showHeroFields(true)
        txtHeroState.visibility = View.GONE
        txtStatus.visibility = View.GONE
        txtEyebrow.text = eyebrowFor(appointment.localDate())
        txtTime.text = getString(
            R.string.appointment_time_format,
            appointment.startTime.orEmpty(),
            appointment.endTime.orEmpty()
        )
        txtWho.text = appointment.personnelName
            ?.takeIf(String::isNotBlank)
            ?.let { getString(R.string.appointment_personnel_format, it) }
            .orEmpty()
        bindTasks(appointment.tasks)
        updateInvitationResponseState()
    }

    private fun showInvitationMode(enabled: Boolean) {
        val enteringInvitation = enabled && invitationPanel.visibility != View.VISIBLE
        invitationTitle.visibility = if (enabled) View.VISIBLE else View.GONE
        invitationPanel.visibility = if (enabled) View.VISIBLE else View.GONE
        // The card's explicit D-pad route previously skipped its response buttons.
        // Let the buttons receive focus directly while an invitation is visible.
        heroCard.isFocusable = !enabled
        heroCard.nextFocusDownId = if (enabled) R.id.btn_home_invitation_accept else R.id.tile_appointments
        appointmentsTile.nextFocusUpId = if (enabled) R.id.btn_home_invitation_accept else R.id.hero_card
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        callTile.nextFocusUpId = if (enabled) R.id.btn_home_invitation_accept
            else if (portrait) R.id.tile_appointments else R.id.hero_card
        dailyTasksTile.nextFocusUpId = if (enabled) R.id.btn_home_invitation_accept
            else if (portrait) R.id.tile_contacts else R.id.hero_card
        if (enteringInvitation) focusInvitationResponse()
        val contentAlpha = if (enabled) 0.42f else 1f
        listOf(appointmentsTile, callTile, dailyTasksTile).forEach { it.alpha = contentAlpha }
        heroCard.contentDescription = getString(
            if (enabled) R.string.appointment_invitation_title else R.string.home_all_appointments
        )
    }

    private fun updateInvitationResponseState() {
        if (!this::invitationActions.isInitialized || currentInvitation == null) return
        val busy = appointmentsViewModel.responseInProgress.value == currentInvitation?.id
        val wasBusy = invitationActions.visibility == View.GONE
        invitationActions.visibility = if (busy) View.GONE else View.VISIBLE
        invitationProgress.visibility = if (busy) View.VISIBLE else View.GONE
        invitationAccept.isEnabled = !busy
        invitationDecline.isEnabled = !busy
        if (wasBusy && !busy) focusInvitationResponse()
    }

    private fun focusInvitationResponse() {
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        if (!isTv) return
        invitationAccept.post {
            if (view != null && currentInvitation != null && invitationAccept.isEnabled &&
                invitationActions.visibility == View.VISIBLE) {
                invitationAccept.requestFocus()
            }
        }
    }

    private fun bindTasks(tasks: String?) {
        taskRow.removeAllViews()
        val paddingHorizontal = resources.getDimensionPixelSize(R.dimen.chip_padding_horizontal)
        val paddingVertical = resources.getDimensionPixelSize(R.dimen.chip_padding_vertical)
        val gap = resources.getDimensionPixelSize(R.dimen.chip_gap)

        tasks.orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { label ->
                val chip = TextView(requireContext()).apply {
                    text = TaskTranslation.translate(requireContext(), label)
                    TextViewCompat.setTextAppearance(this, R.style.VK_Text_Caption)
                    setBackgroundResource(R.drawable.task_chip_background)
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.vk_text_primary))
                    setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                    gravity = Gravity.CENTER
                    maxLines = 1
                }
                taskRow.addView(
                    chip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = gap }
                )
            }
    }

    private fun eyebrowFor(date: LocalDate?): String {
        val today = LocalDate.now()
        return when (date) {
            null -> ""
            today -> getString(R.string.appointment_hero_today)
            today.plusDays(1) -> getString(R.string.appointment_hero_tomorrow)
            else -> AppointmentStatusUi.formatLongDate(date.toString())
        }
    }

    private fun heroMessage(messageRes: Int) {
        showHeroFields(false)
        txtHeroState.visibility = View.VISIBLE
        txtHeroState.setText(messageRes)
    }

    private fun showHeroFields(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        txtEyebrow.visibility = visibility
        txtTime.visibility = visibility
        txtWho.visibility = visibility
        txtStatus.visibility = visibility
        taskRow.visibility = visibility
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
