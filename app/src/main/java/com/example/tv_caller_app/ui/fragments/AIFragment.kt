package com.example.tv_caller_app.ui.fragments

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.data.repository.AppointmentRepository
import com.example.tv_caller_app.data.repository.PatientProfileRepository
import com.example.tv_caller_app.data.datasource.ProfileNotLinkedException
import com.example.tv_caller_app.domain.Appointment
import com.example.tv_caller_app.domain.CareTeamMember
import com.example.tv_caller_app.domain.PatientProfile
import com.example.tv_caller_app.domain.isUpcoming
import com.example.tv_caller_app.domain.startDateTime
import com.example.tv_caller_app.ui.activities.MainActivity
import com.example.tv_caller_app.ui.util.TaskTranslation
import com.example.tv_caller_app.ui.util.showVikomMessage
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Patient-friendly help hub backed by the same care record as the web portal. */
class AIFragment : Fragment() {

    companion object {
        private const val ARG_OPEN_TOPIC = "open_topic"
        private const val TOPIC_DAILY = "daily"

        fun forDailyTasks(): AIFragment = AIFragment().apply {
            arguments = Bundle().apply { putString(ARG_OPEN_TOPIC, TOPIC_DAILY) }
        }
    }

    private data class TopicContent(
        val title: String,
        val subtitle: String,
        val items: List<String>
    )

    private var profile = PatientProfile()
    private var appointments: List<Appointment> = emptyList()
    private var careTeam: List<CareTeamMember> = emptyList()
    private var profileLoadState: Boolean? = null
    private var appointmentsLoadState: Boolean? = null
    private var requestedTopicOpened = false

    private lateinit var greeting: TextView
    private lateinit var headNurseCard: View
    private lateinit var headNurseName: TextView
    private lateinit var headNursePhone: TextView
    private lateinit var medicineSummary: TextView
    private lateinit var appointmentsSummary: TextView
    private lateinit var dailySummary: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_ai, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(view, getString(R.string.screen_ai))
        (activity as? MainActivity)?.hideHeader()

        greeting = view.findViewById(R.id.txt_ai_greeting)
        headNurseCard = view.findViewById(R.id.card_head_nurse)
        headNurseName = view.findViewById(R.id.txt_head_nurse_name)
        headNursePhone = view.findViewById(R.id.txt_head_nurse_phone)
        medicineSummary = view.findViewById(R.id.txt_medicine_summary)
        appointmentsSummary = view.findViewById(R.id.txt_appointments_summary)
        dailySummary = view.findViewById(R.id.txt_daily_summary)

        greeting.text = getString(R.string.ai_greeting_loading)
        medicineSummary.setText(R.string.ai_loading_care_data)
        appointmentsSummary.setText(R.string.ai_loading_care_data)
        dailySummary.setText(R.string.ai_loading_care_data)
        headNurseName.setText(R.string.ai_loading_care_data)
        headNursePhone.text = ""

        view.findViewById<TextView>(R.id.btn_ai_confirm).apply {
            announceAsButton()
            setOnClickListener { (activity as? MainActivity)?.showHome() }
        }
        view.findViewById<LinearLayout>(R.id.topic_medicine).setOnClickListener {
            showTopicDialog(medicineContent())
        }
        view.findViewById<LinearLayout>(R.id.topic_appointments).setOnClickListener {
            showTopicDialog(appointmentsContent())
        }
        view.findViewById<LinearLayout>(R.id.topic_daily).setOnClickListener {
            showTopicDialog(dailyContent())
        }
        view.findViewById<LinearLayout>(R.id.topic_help).setOnClickListener {
            showHelpDialog()
        }
        headNurseCard.setOnClickListener { showHeadNurseContact() }

        view.findViewById<LinearLayout>(R.id.topic_medicine).requestFocus()
        loadCareData()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? MainActivity)?.showHeader()
    }

    private fun loadCareData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as TVCallerApplication
            val session = SessionManager.getInstance(requireContext())
            val profileRepository = PatientProfileRepository.getInstance(session, app.getAuthRepository())
            val appointmentRepository = AppointmentRepository.getInstance(session, app.getAuthRepository())

            // Home already warms profile and appointment data. Reuse those
            // caches so the help screen fills immediately, while normal cache
            // invalidation still keeps edits and realtime changes authoritative.
            val profileRequest = async { runCatching { profileRepository.getMyProfile(forceRefresh = false) } }
            val teamRequest = async { runCatching { profileRepository.getMyCareTeam(forceRefresh = false) } }
            val appointmentRequest = async { runCatching { appointmentRepository.getMyAppointments(forceRefresh = false) } }

            val profileResult = profileRequest.await()
            val teamResult = teamRequest.await()
            val appointmentsResult = appointmentRequest.await()

            // An unlinked account is an ordinary ViKom user, not a failed
            // patient-data request. Render honest empty states for care data;
            // keep "could not load" for real network/server failures only.
            val profileAvailable = profileResult.isSuccess ||
                profileResult.exceptionOrNull() is ProfileNotLinkedException
            val teamAvailable = teamResult.isSuccess ||
                teamResult.exceptionOrNull() is ProfileNotLinkedException
            val appointmentsAvailable = appointmentsResult.isSuccess ||
                appointmentsResult.exceptionOrNull() is ProfileNotLinkedException

            profileResult.onSuccess { profile = it }
            if (profileResult.exceptionOrNull() is ProfileNotLinkedException) {
                profile = PatientProfile(userName = session.getUsername().orEmpty())
            }
            teamResult.onSuccess { careTeam = it }
            appointmentsResult.onSuccess { appointments = it }
            profileLoadState = profileAvailable
            appointmentsLoadState = appointmentsAvailable

            if (!isAdded) return@launch
            renderCareData(profileAvailable, teamAvailable, appointmentsAvailable)
        }
    }

    private fun renderCareData(profileLoaded: Boolean, teamLoaded: Boolean, appointmentsLoaded: Boolean) {
        val displayName = profile.fullName.takeIf(String::isNotBlank)
            ?: profile.userName.takeIf(String::isNotBlank)
        greeting.text = if (displayName != null) {
            getString(R.string.ai_greeting, displayName)
        } else {
            getString(R.string.ai_greeting_generic)
        }

        medicineSummary.text = when {
            !profileLoaded -> getString(R.string.ai_data_unavailable)
            profile.medications.isEmpty() -> getString(R.string.ai_no_medicines_short)
            else -> resources.getQuantityString(
                R.plurals.ai_medicine_count,
                profile.medications.size,
                profile.medications.size
            )
        }

        val nextAppointments = upcomingAppointments()
        appointmentsSummary.text = when {
            !appointmentsLoaded -> getString(R.string.ai_data_unavailable)
            nextAppointments.isEmpty() -> getString(R.string.appointments_none_upcoming)
            else -> resources.getQuantityString(
                R.plurals.ai_appointment_count,
                nextAppointments.size,
                nextAppointments.size
            )
        }

        val tasks = parsedDailyTasks()
        dailySummary.text = when {
            !profileLoaded -> getString(R.string.ai_data_unavailable)
            tasks.isEmpty() -> getString(R.string.ai_no_daily_tasks_short)
            else -> resources.getQuantityString(R.plurals.ai_daily_task_count, tasks.size, tasks.size)
        }

        val headNurse = headNurse()
        headNurseCard.isEnabled = teamLoaded && headNurse != null
        headNurseName.text = when {
            !teamLoaded -> getString(R.string.ai_data_unavailable)
            headNurse == null -> getString(R.string.ai_head_nurse_missing)
            else -> headNurse.fullName
        }
        headNursePhone.text = headNurse?.phoneNumber?.takeIf(String::isNotBlank)
            ?: getString(R.string.ai_phone_missing)
        headNurseCard.contentDescription = getString(
            R.string.ai_head_nurse_accessibility,
            headNurseName.text,
            headNursePhone.text
        )

        if (!requestedTopicOpened && arguments?.getString(ARG_OPEN_TOPIC) == TOPIC_DAILY) {
            requestedTopicOpened = true
            showTopicDialog(dailyContent())
        }
    }

    private fun medicineContent(): TopicContent {
        if (profileLoadState != true) {
            return TopicContent(
                getString(R.string.ai_topic_medicine),
                getString(R.string.ai_medicine_subtitle),
                listOf(getString(if (profileLoadState == null) R.string.ai_loading_care_data else R.string.ai_data_unavailable))
            )
        }
        val items = profile.medications.map { medication ->
            listOfNotNull(
                medication.name.takeIf(String::isNotBlank),
                medication.dosage?.takeIf(String::isNotBlank),
                medication.schedule?.takeIf(String::isNotBlank)
            ).joinToString("  ·  ")
        }.filter(String::isNotBlank)
        return TopicContent(
            getString(R.string.ai_topic_medicine),
            getString(R.string.ai_medicine_subtitle),
            items.ifEmpty { listOf(getString(R.string.ai_no_medicines)) }
        )
    }

    private fun appointmentsContent(): TopicContent {
        if (appointmentsLoadState != true) {
            return TopicContent(
                getString(R.string.ai_topic_appointments),
                getString(R.string.ai_appointments_subtitle),
                listOf(getString(if (appointmentsLoadState == null) R.string.ai_loading_care_data else R.string.ai_data_unavailable))
            )
        }
        val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
        val items = upcomingAppointments().take(5).map { appointment ->
            val date = appointment.startDateTime()?.toLocalDate()?.format(dateFormatter)
                ?: appointment.date.orEmpty()
            val time = getString(
                R.string.appointment_time_format,
                appointment.startTime.orEmpty(),
                appointment.endTime.orEmpty()
            )
            val details = listOfNotNull(
                appointment.personnelName?.takeIf(String::isNotBlank),
                appointment.tasks?.takeIf(String::isNotBlank)
                    ?.let { TaskTranslation.translateList(requireContext(), it) }
            ).joinToString(" · ")
            if (details.isBlank()) "$date  ·  $time" else "$date  ·  $time\n$details"
        }
        return TopicContent(
            getString(R.string.ai_topic_appointments),
            getString(R.string.ai_appointments_subtitle),
            items.ifEmpty { listOf(getString(R.string.appointments_none_upcoming)) }
        )
    }

    private fun dailyContent(): TopicContent {
        val items = if (profileLoadState == true) {
            parsedDailyTasks().ifEmpty { listOf(getString(R.string.ai_no_daily_tasks)) }
        } else {
            listOf(getString(if (profileLoadState == null) R.string.ai_loading_care_data else R.string.ai_data_unavailable))
        }
        return TopicContent(
            getString(R.string.ai_topic_daily_tasks),
            getString(R.string.ai_daily_subtitle),
            items
        )
    }

    private fun upcomingAppointments(): List<Appointment> = appointments
        .asSequence()
        .filter { appointment ->
            (appointment.status.equals("Booked", ignoreCase = true) ||
                appointment.status.equals("InProgress", ignoreCase = true)) &&
                !appointment.patientResponseStatus.equals("Declined", ignoreCase = true) &&
                appointment.isUpcoming()
        }
        .sortedBy { it.startDateTime() }
        .toList()

    private fun parsedDailyTasks(): List<String> = profile.dailyTasks.orEmpty()
        .split(Regex("[\\r\\n;]+"))
        .map(String::trim)
        .filter(String::isNotBlank)

    private fun headNurse(): CareTeamMember? = careTeam.firstOrNull {
        it.role.equals("HeadNurse", ignoreCase = true)
    } ?: careTeam.firstOrNull {
        it.relationshipType.equals("Personnel", ignoreCase = true)
    }

    private fun showHeadNurseContact() {
        val nurse = headNurse() ?: return
        val phone = nurse.phoneNumber?.takeIf(String::isNotBlank)
        if (phone == null) {
            showVikomMessage(
                requireContext(),
                getString(R.string.ai_head_nurse_title),
                getString(R.string.ai_phone_missing)
            )
            return
        }
        (activity as? MainActivity)?.showConfirmDialog(
            title = getString(R.string.ai_call_confirm_title, nurse.fullName),
            message = getString(R.string.ai_call_head_nurse_message, nurse.fullName, phone),
            icon = "📞"
        ) { openPhoneNumber(phone) }
    }

    private fun showHelpDialog() {
        val nurse = headNurse()
        val items = mutableListOf(
            getString(R.string.ai_emergency_medical),
            getString(R.string.ai_help_out_of_hours_number),
            getString(R.string.ai_help_fire_number),
            getString(R.string.ai_help_police_number)
        )
        nurse?.let {
            items.add(0, getString(R.string.ai_head_nurse_contact_line, it.fullName, it.phoneNumber.orEmpty()))
        }
        showTopicDialog(
            TopicContent(
                getString(R.string.ai_help_title),
                getString(R.string.ai_help_subtitle),
                items
            )
        )
    }

    private fun openPhoneNumber(phone: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            showVikomMessage(
                requireContext(),
                getString(R.string.ai_phone_unavailable_title),
                getString(R.string.ai_phone_unavailable_message, phone)
            )
        }
    }

    private fun showTopicDialog(content: TopicContent) {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_ai_topic)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setCancelable(true)

        dialog.findViewById<TextView>(R.id.txt_topic_title).text = content.title
        dialog.findViewById<TextView>(R.id.txt_topic_subtitle).text = content.subtitle
        dialog.findViewById<TextView>(R.id.txt_topic_date).visibility = View.GONE

        val container = dialog.findViewById<LinearLayout>(R.id.items_container)
        val horizontal = resources.getDimensionPixelSize(R.dimen.padding_standard)
        val vertical = resources.getDimensionPixelSize(R.dimen.padding_medium)
        content.items.forEachIndexed { index, item ->
            container.addView(TextView(requireContext()).apply {
                text = item
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_primary))
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(horizontal, vertical, horizontal, vertical)
                background = ContextCompat.getDrawable(requireContext(), R.drawable.button_dialog_item)
                isFocusable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (index > 0) it.topMargin = resources.getDimensionPixelSize(R.dimen.padding_small) }
            })
        }

        val close = dialog.findViewById<TextView>(R.id.btn_topic_close)
        close.announceAsButton()
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()

        val portrait = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * if (portrait) 0.92 else 0.62).toInt(),
            (resources.displayMetrics.heightPixels * if (portrait) 0.86 else 0.82).toInt()
        )
        (container.getChildAt(0) ?: close).requestFocus()
    }

    private fun View.announceAsButton() {
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        })
    }
}
