package com.example.tv_caller_app.ui.activities

import android.app.Dialog
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.Button
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.app.ActivityCompat
import androidx.activity.OnBackPressedCallback
import android.content.pm.PackageManager
import android.os.Build
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.drawable.toDrawable
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.fragments.AIFragment
import com.example.tv_caller_app.ui.fragments.HamburgerMenuFragment
import com.example.tv_caller_app.ui.fragments.HomeFragment
import com.example.tv_caller_app.ui.fragments.AppointmentListFragment
import com.example.tv_caller_app.data.repository.AppointmentRepository
import com.example.tv_caller_app.data.datasource.ProfileNotLinkedException
import com.example.tv_caller_app.data.datasource.SessionExpiredException
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.ui.viewmodel.AppointmentsViewModel
import com.example.tv_caller_app.ui.viewmodel.AppointmentsViewModelFactory
import com.example.tv_caller_app.ui.fragments.ProfileFragment
import com.example.tv_caller_app.ui.fragments.SettingsFragment
import com.example.tv_caller_app.settings.SettingsManager
import com.example.tv_caller_app.domain.AppointmentEvent
import com.example.tv_caller_app.domain.canRespond
import com.example.tv_caller_app.ui.util.showInvitationRequiredMessage
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel
import com.example.tv_caller_app.ui.viewmodel.CallViewModel

class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_APPOINTMENTS = "appointments"
        private const val REQUEST_NOTIFICATIONS = 1201

        fun createIntent(context: Context, destination: String? = null): Intent =
            Intent(context, MainActivity::class.java).apply {
                destination?.let { putExtra(EXTRA_DESTINATION, it) }
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }

    private lateinit var headerBar: RelativeLayout
    private lateinit var txtWelcome: TextView
    private lateinit var txtClockTime: TextView
    private lateinit var txtClockDate: TextView
    private lateinit var authViewModel: AuthViewModel
    private lateinit var appointmentsViewModel: AppointmentsViewModel
    private var hasCheckedOnboarding = false
    private var observedCallViewModel: CallViewModel? = null
    private var observesAppointmentEvents = false
    private var sessionIsValid = false
    private var invitationGateActive = false
    private var checkInRequestInFlight = false
    private var dailyCheckInDialog: Dialog? = null
    private var missedCallDialog: Dialog? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 30000) 
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLocale()
        setContentView(R.layout.activity_main)
        ViewCompat.setAccessibilityPaneTitle(window.decorView, getString(R.string.screen_home))

        headerBar = findViewById(R.id.header_bar)
        txtWelcome = findViewById(R.id.txt_welcome)
        txtClockTime = findViewById(R.id.txt_clock_time)
        txtClockDate = findViewById(R.id.txt_clock_date)

        val authRepository = (application as com.example.tv_caller_app.TVCallerApplication).getAuthRepository()
        authViewModel = ViewModelProvider(this, AuthViewModel.factory(authRepository))[AuthViewModel::class.java]
        appointmentsViewModel = ViewModelProvider(
            this,
            AppointmentsViewModelFactory(application)
        )[AppointmentsViewModel::class.java]

        observeAuthViewModel(savedInstanceState)
        observePendingInvitations()
        observeSessionExpiry()
        setupHamburgerMenu()
        setupProfileButton()
        setupSettingsButton()
        applyMenuLabelVisibility()
        updateClock()
        clockHandler.postDelayed(clockRunnable, 30000)
        authViewModel.checkSession()
        setupAiAssistantButton()
        findViewById<View>(R.id.header_left)?.setOnClickListener { showHome() }
        setupBackNavigation()
        syncHeaderButtonHeights()
    }

    private fun syncHeaderButtonHeights() {
        val clockContainer = findViewById<LinearLayout>(R.id.clock_container)
        val btnAiAssistant = findViewById<LinearLayout>(R.id.btn_ai_assistant)
        val iconsContainer = findViewById<LinearLayout>(R.id.icons_container)

        clockContainer.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val height = clockContainer.height
                if (height == 0) return
                clockContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                listOf(btnAiAssistant, iconsContainer).forEach { view ->
                    view?.layoutParams = view?.layoutParams?.apply { this.height = height }
                    view?.requestLayout()
                }
            }
        })
    }

    private fun setupAiAssistantButton() {
        findViewById<LinearLayout>(R.id.btn_ai_assistant)?.setOnClickListener {
            navigateUnlessInvitationPending {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.main_browse_fragment, AIFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    private fun applyLocale() {
        val lang = SettingsManager.getInstance(this).language
        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun updateClock() {
        val now = Date()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        txtClockTime.text = timeFormat.format(now)
        txtClockDate.text = dateFormat.format(now)
    }

    private fun loadMainContent(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, HomeFragment())
                .commitNow()
            openIntentDestination(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIntentDestination(intent)
    }

    private fun openIntentDestination(intent: Intent) {
        if (intent.getStringExtra(EXTRA_DESTINATION) != DESTINATION_APPOINTMENTS) return
        if (invitationGateActive) {
            showHomeContent()
            showInvitationReminder()
            intent.removeExtra(EXTRA_DESTINATION)
            return
        }
        if (supportFragmentManager.findFragmentById(R.id.main_browse_fragment) is AppointmentListFragment) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_browse_fragment, AppointmentListFragment())
            .addToBackStack(null)
            .commit()
        intent.removeExtra(EXTRA_DESTINATION)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        }
    }

    private fun observeAppointmentEvents() {
        if (observesAppointmentEvents) return
        observesAppointmentEvents = true
        val app = application as com.example.tv_caller_app.TVCallerApplication
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.getAppointmentEventFlow().collect { event ->
                    AppointmentRepository.getInstance(
                        SessionManager.getInstance(this@MainActivity),
                        app.getAuthRepository()
                    ).invalidateCache()
                    appointmentsViewModel.load(forceRefresh = true)

                    if (sessionIsValid && event.isPendingInvitation() && !isCallInProgress()) {
                        setInvitationGate(true)
                        showHomeContent()
                    }
                }
            }
        }
    }

    /**
     * A pending invitation is a top-level task, not something the patient has to
     * discover in a submenu. The first unanswered future appointment is opened
     * after every fresh launch; realtime events use the same screen immediately.
     */
    private fun observePendingInvitations() {
        appointmentsViewModel.uiState.observe(this) { state ->
            when (state) {
                is com.example.tv_caller_app.ui.viewmodel.AppointmentsUiState.Content -> {
                    val hasPendingInvitation = state.appointments
                        .asSequence()
                        .filter { it.canRespond() }
                        .any()
                    setInvitationGate(hasPendingInvitation)
                    if (hasPendingInvitation && !isCallInProgress()) showHomeContent()
                }
                is com.example.tv_caller_app.ui.viewmodel.AppointmentsUiState.Empty,
                is com.example.tv_caller_app.ui.viewmodel.AppointmentsUiState.NotLinked,
                is com.example.tv_caller_app.ui.viewmodel.AppointmentsUiState.NotConfigured,
                is com.example.tv_caller_app.ui.viewmodel.AppointmentsUiState.SessionExpired -> {
                    setInvitationGate(false)
                }
                else -> Unit
            }
        }
    }

    private fun AppointmentEvent.isPendingInvitation(): Boolean {
        return appointmentId > 0 &&
            status.equals("Booked", ignoreCase = true) &&
            patientResponseStatus.equals("Pending", ignoreCase = true)
    }

    private fun observeIncomingCalls() {
        val app = application as com.example.tv_caller_app.TVCallerApplication

        if (app.getCallViewModel() == null) {
            val sessionManager = com.example.tv_caller_app.auth.SessionManager.getInstance(this)
            if (!sessionManager.isLoggedIn()) return
            Log.d(TAG, "CallViewModel null on resume — re-initializing")
            app.initializeCallViewModel()
            com.example.tv_caller_app.calling.service.SignalingForegroundService.startService(this)
        }

        val callViewModel = app.getCallViewModel() ?: return

        if (callViewModel === observedCallViewModel) return

        observedCallViewModel?.callState?.removeObservers(this)
        observedCallViewModel?.missedCall?.removeObservers(this)
        observedCallViewModel = callViewModel
        Log.d(TAG, "Attaching call state observer to CallViewModel instance")

        callViewModel.callState.observe(this) { state ->
            Log.d(TAG, "Call state changed: $state")
            when (state) {
                is CallViewModel.CallState.RingingIncoming -> {
                    Log.i(TAG, "Incoming call detected - launching IncomingCallActivity")
                    val intent = IncomingCallActivity.createIntent(
                        context = this,
                        callerId = state.callerId,
                        callerName = state.callerName,
                        callId = state.callerId + "_" + System.currentTimeMillis(),
                        callerPhone = null,
                        nickname = state.nickname,
                        avatarUrl = state.avatarUrl,
                        callType = state.callType
                    )
                    startActivity(intent)
                }
                is CallViewModel.CallState.RingingOutgoing -> {
                    Log.i(TAG, "Outgoing call initiated - launching OutgoingCallActivity")
                    val intent = OutgoingCallActivity.createIntent(
                        context = this,
                        contactId = state.targetUserId,
                        contactName = state.contactName,
                        callId = state.targetUserId + "_" + System.currentTimeMillis(),
                        contactPhone = null,
                        nickname = state.nickname,
                        avatarUrl = state.avatarUrl,
                        callType = state.callType
                    )
                    startActivity(intent)
                }
                else -> {
                    Log.d(TAG, "Call state: $state (no action needed in MainActivity)")
                }
            }
        }

        callViewModel.missedCall.observe(this) { notice ->
            if (notice != null && !isCallInProgress()) {
                showMissedCallDialog(notice)
            }
        }
    }

    private fun showMissedCallDialog(notice: CallViewModel.MissedCallNotice) {
        missedCallDialog?.dismiss()
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_logout_confirm)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        dialog.findViewById<TextView>(R.id.dialog_icon).text = "☎"
        dialog.findViewById<TextView>(R.id.dialog_title).setText(R.string.missed_call_title)
        dialog.findViewById<TextView>(R.id.dialog_message).text = getString(
            if (notice.mediaType.equals("video", ignoreCase = true)) {
                R.string.missed_video_call_message
            } else {
                R.string.missed_audio_call_message
            },
            notice.callerName.ifBlank { getString(R.string.unknown_contact) }
        )
        val okay = dialog.findViewById<TextView>(R.id.btn_confirm)
        val secondary = dialog.findViewById<TextView>(R.id.btn_cancel)
        okay.setText(R.string.ok)
        secondary.visibility = View.GONE
        okay.setOnClickListener {
            observedCallViewModel?.clearMissedCallNotice()
            dialog.dismiss()
        }
        dialog.setOnDismissListener { if (missedCallDialog === dialog) missedCallDialog = null }
        missedCallDialog = dialog
        dialog.show()
        val maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val preferred = resources.getDimensionPixelSize(R.dimen.dialog_width)
        dialog.window?.setLayout(preferred.coerceAtMost(maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        okay.requestFocus()
    }

    private fun observeSessionExpiry() {
        val sessionManager = com.example.tv_caller_app.auth.SessionManager.getInstance(this)
        lifecycleScope.launch {
            sessionManager.sessionExpired.collect {
                Log.w(TAG, "Session expired signal received — redirecting to auth")
                val app = application as com.example.tv_caller_app.TVCallerApplication
                app.invalidateAllCaches()
                app.stopSessionRefresh()
                app.stopPresence()
                app.cleanupCallViewModel()
                redirectToAuth()
            }
        }
    }

    private fun redirectToAuth() {
        val intent = Intent(this, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setupHamburgerMenu() {
        val isLargeScreen = resources.getBoolean(R.bool.is_large_screen)
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        if (!isTv) {
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val iconsContainer = findViewById<LinearLayout>(R.id.icons_container)
            val hamburgerContainer = findViewById<LinearLayout>(R.id.btn_hamburger_container)
            val btnHamburger = findViewById<ImageView>(R.id.btn_hamburger)
            val clockSpacer = findViewById<View>(R.id.clock_spacer)
            val clockContainer = findViewById<LinearLayout>(R.id.clock_container)

            val btnAiAssistant = findViewById<LinearLayout>(R.id.btn_ai_assistant)
            if (isLargeScreen && isLandscape) {
                hamburgerContainer.visibility = View.GONE
                iconsContainer.visibility = View.VISIBLE
                clockContainer.visibility = View.VISIBLE
                clockSpacer.visibility = View.VISIBLE
            } else {
                iconsContainer.visibility = View.GONE
                clockSpacer.visibility = View.GONE
                clockContainer.visibility = View.GONE
                hamburgerContainer.visibility = View.VISIBLE
                btnAiAssistant?.visibility = View.GONE

                btnHamburger.setOnClickListener {
                    navigateUnlessInvitationPending {
                        Log.d(TAG, "Hamburger menu clicked")
                        HamburgerMenuFragment().show(supportFragmentManager, "hamburger_menu")
                    }
                }
            }
        }
    }

    private fun setupProfileButton() {
        findViewById<LinearLayout>(R.id.btn_profile)?.setOnClickListener {
            navigateUnlessInvitationPending {
                Log.d(TAG, "Profile button clicked")
                supportFragmentManager.beginTransaction()
                    .replace(R.id.main_browse_fragment, ProfileFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    private fun setupSettingsButton() {
        findViewById<LinearLayout>(R.id.btn_settings)?.setOnClickListener {
            navigateUnlessInvitationPending {
                Log.d(TAG, "Settings button clicked")
                supportFragmentManager.beginTransaction()
                    .replace(R.id.main_browse_fragment, SettingsFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
    }

    private fun applyMenuLabelVisibility() {
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_TELEVISION
        val isLargeScreen = resources.getBoolean(R.bool.is_large_screen)
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val visibility = if (isTv || (isLargeScreen && isLandscape)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.txt_settings_label)?.visibility = visibility
        findViewById<TextView>(R.id.txt_profile_label)?.visibility = visibility
    }

    private fun observeAuthViewModel(savedInstanceState: Bundle?) {
        authViewModel.sessionValid.observe(this) { isValid ->
            when (isValid) {
                true -> {
                    Log.d(TAG, "Valid session - loading main content")
                    sessionIsValid = true
                    authViewModel.cachedUsername.observe(this) { username ->
                        if (!username.isNullOrBlank()) {
                            txtWelcome.text = getString(R.string.welcome_named_user, username)
                        }
                    }
                    if (!hasCheckedOnboarding) {
                        hasCheckedOnboarding = true
                        val profileRepository = (application as com.example.tv_caller_app.TVCallerApplication).getProfileRepository()
                        lifecycleScope.launch {
                            val hasSeen = profileRepository.hasSeenOnboarding()
                            if (!hasSeen && !isFinishing && !isDestroyed) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (!isFinishing && !isDestroyed) {
                                        if (!invitationGateActive && dailyCheckInDialog?.isShowing != true) {
                                            startActivity(OnboardingActivity.createIntent(this@MainActivity))
                                        }
                                    }
                                }, 1500L)
                            }
                        }
                    }
                    val app = application as com.example.tv_caller_app.TVCallerApplication
                    app.startPresence("tv")
                    app.initializeCallViewModel()
                    com.example.tv_caller_app.calling.service.SignalingForegroundService.startService(this)
                    observeIncomingCalls()
                    observeAppointmentEvents()
                    requestNotificationPermissionIfNeeded()
                    loadMainContent(savedInstanceState)
                    appointmentsViewModel.load(forceRefresh = true)
                    checkDailySafetyStatus()
                }
                false -> {
                    sessionIsValid = false
                    Log.d(TAG, "No valid session - redirecting to AuthActivity")
                    redirectToAuth()
                }
                null -> {} 
            }
        }

        authViewModel.loggedOut.observe(this) { loggedOut ->
            if (loggedOut) {
                val app = application as com.example.tv_caller_app.TVCallerApplication
                app.invalidateAllCaches()
                app.stopSessionRefresh()
                app.stopPresence()
                app.cleanupCallViewModel()
                redirectToAuth()
            }
        }

        authViewModel.errorMessage.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_LONG).show()
                authViewModel.clearError()
            }
        }
    }

    fun showLogoutConfirmDialog() {
        showConfirmDialog(
            title = getString(R.string.logout_confirm_title),
            message = getString(R.string.logout_confirm_message),
            icon = "⚠️",
            onConfirm = { performLogout() }
        )
    }

    fun showConfirmDialog(
        title: String,
        message: String,
        icon: String = "⚠️",
        onConfirm: () -> Unit
    ) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_logout_confirm)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setCancelable(true)

        dialog.findViewById<TextView>(R.id.dialog_icon).text = icon
        dialog.findViewById<TextView>(R.id.dialog_title).text = title
        dialog.findViewById<TextView>(R.id.dialog_message).text = message

        val btnConfirm = dialog.findViewById<TextView>(R.id.btn_confirm)
        val btnCancel = dialog.findViewById<TextView>(R.id.btn_cancel)

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        btnCancel.requestFocus()
    }

    private fun performLogout() {
        Log.d(TAG, "Performing logout")
        lifecycleScope.launch {
            (application as com.example.tv_caller_app.TVCallerApplication).clearFcmToken()
            authViewModel.logout()
        }
    }

    fun showHeader() {
        headerBar.visibility = View.VISIBLE
    }

    fun hideHeader() {
        headerBar.visibility = View.GONE
    }

    /** Returns to the simple patient home from any main-area screen. */
    fun showHome() {
        if (invitationGateActive) {
            showHomeContent()
            showInvitationReminder()
            return
        }
        showHomeContent()
    }

    private fun showHomeContent() {
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        if (supportFragmentManager.findFragmentById(R.id.main_browse_fragment) !is HomeFragment) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, HomeFragment())
                .commit()
        }
        showHeader()
    }

    fun setInvitationGate(active: Boolean) {
        invitationGateActive = active
        if (this::headerBar.isInitialized) {
            findViewById<View>(R.id.header_left)?.alpha = if (active) 0.55f else 1f
            findViewById<View>(R.id.header_right)?.alpha = if (active) 0.55f else 1f
        }
    }

    fun showInvitationReminder() {
        showInvitationRequiredMessage(this)
    }

    private fun navigateUnlessInvitationPending(action: () -> Unit) {
        if (invitationGateActive) showInvitationReminder() else action()
    }

    private fun isCallInProgress(): Boolean = when (
        (application as? com.example.tv_caller_app.TVCallerApplication)
            ?.getCallViewModel()?.callState?.value
    ) {
        is CallViewModel.CallState.Initiating,
        is CallViewModel.CallState.RingingOutgoing,
        is CallViewModel.CallState.RingingIncoming,
        is CallViewModel.CallState.Connecting,
        is CallViewModel.CallState.Connected,
        is CallViewModel.CallState.Ending -> true
        else -> false
    }

    private fun checkDailySafetyStatus() {
        if (!sessionIsValid || checkInRequestInFlight || isCallInProgress()) {
            return
        }
        val session = SessionManager.getInstance(this)

        // A previously confirmed web patient is blocked immediately on later
        // launches. New/unlinked accounts are checked quietly so ordinary ViKom
        // users never see a patient-only prompt.
        if (session.dailyCheckInRequired() == true) {
            showDailyCheckInDialog(checkingStatus = true)
        }

        checkInRequestInFlight = true
        lifecycleScope.launch {
            val result = (application as com.example.tv_caller_app.TVCallerApplication)
                .getDailyCheckInRepository()
                .getStatus()
            checkInRequestInFlight = false

            val failure = result.exceptionOrNull()
            if (failure is ProfileNotLinkedException) {
                session.saveDailyCheckInRequired(false)
                dailyCheckInDialog?.dismiss()
                return@launch
            }
            if (failure is SessionExpiredException) {
                dailyCheckInDialog?.dismiss()
                return@launch
            }

            if (failure != null) {
                // Fail closed only for an account already known to be a patient.
                // For an ordinary/unknown account, a backend outage must not add
                // a patient restriction to the rest of ViKom.
                if (session.dailyCheckInRequired() == true) {
                    prepareDailyCheckInConfirmation(getString(R.string.daily_check_in_error))
                } else {
                    dailyCheckInDialog?.dismiss()
                }
                return@launch
            }

            val status = result.getOrThrow()
            session.saveDailyCheckInRequired(status.required)
            val confirmationNeeded = status.required && !status.checkedInToday

            if (!confirmationNeeded) {
                dailyCheckInDialog?.dismiss()
            } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                prepareDailyCheckInConfirmation(errorMessage = null)
            }
        }
    }

    private fun showDailyCheckInDialog(checkingStatus: Boolean = false) {
        if (dailyCheckInDialog?.isShowing == true) {
            if (checkingStatus) {
                dailyCheckInDialog?.findViewById<Button>(R.id.btn_confirm_daily_check_in)?.isEnabled = false
                dailyCheckInDialog?.findViewById<ProgressBar>(R.id.check_in_progress)?.visibility = View.VISIBLE
                dailyCheckInDialog?.findViewById<TextView>(R.id.txt_check_in_error)?.visibility = View.GONE
            }
            return
        }

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_daily_check_in)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        val button = dialog.findViewById<Button>(R.id.btn_confirm_daily_check_in)
        val progress = dialog.findViewById<ProgressBar>(R.id.check_in_progress)
        val error = dialog.findViewById<TextView>(R.id.txt_check_in_error)

        button.isEnabled = !checkingStatus
        progress.visibility = if (checkingStatus) View.VISIBLE else View.GONE
        error.visibility = View.GONE

        button.setOnClickListener {
            button.isEnabled = false
            error.visibility = View.GONE
            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = (application as com.example.tv_caller_app.TVCallerApplication)
                    .getDailyCheckInRepository()
                    .confirmToday()
                progress.visibility = View.GONE
                val status = result.getOrNull()
                if (status != null && (!status.required || status.checkedInToday)) {
                    SessionManager.getInstance(this@MainActivity)
                        .saveDailyCheckInRequired(status.required)
                    dialog.dismiss()
                } else {
                    error.setText(R.string.daily_check_in_error)
                    error.visibility = View.VISIBLE
                    button.isEnabled = true
                    button.requestFocus()
                }
            }
        }

        dialog.setOnDismissListener { dailyCheckInDialog = null }
        dailyCheckInDialog = dialog
        dialog.show()
        val maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val preferred = (560 * resources.displayMetrics.density).toInt()
        dialog.window?.setLayout(preferred.coerceAtMost(maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        button.requestFocus()
    }

    private fun prepareDailyCheckInConfirmation(errorMessage: String?) {
        val dialog = dailyCheckInDialog ?: run {
            showDailyCheckInDialog()
            dailyCheckInDialog
        } ?: return

        dialog.findViewById<ProgressBar>(R.id.check_in_progress).visibility = View.GONE
        dialog.findViewById<TextView>(R.id.txt_check_in_error).apply {
            text = errorMessage ?: getString(R.string.daily_check_in_error)
            visibility = if (errorMessage != null) View.VISIBLE else View.GONE
        }
        dialog.findViewById<Button>(R.id.btn_confirm_daily_check_in).apply {
            isEnabled = true
            requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        checkForActiveCall()
        observeIncomingCalls()
        if (sessionIsValid) {
            appointmentsViewModel.load(forceRefresh = true)
            checkDailySafetyStatus()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (invitationGateActive) {
                    showHomeContent()
                    showInvitationReminder()
                    return
                }

                // Delegate to the normal FragmentActivity behavior while this
                // callback is temporarily out of the dispatcher chain.
                isEnabled = false
                try {
                    onBackPressedDispatcher.onBackPressed()
                } finally {
                    isEnabled = true
                }
            }
        })
    }

    private fun checkForActiveCall() {
        val app = application as? com.example.tv_caller_app.TVCallerApplication ?: return
        val callViewModel = app.getCallViewModel() ?: return

        when (val state = callViewModel.callState.value) {
            is CallViewModel.CallState.Connected -> {
                Log.i(TAG, "Active call detected on resume - relaunching InCallActivity")
                val intent = InCallActivity.createIntent(
                    context = this,
                    contactId = state.remoteUserId,
                    contactName = state.remoteUserName,
                    callId = state.remoteUserId,
                    isIncoming = state.isIncoming,
                    nickname = state.nickname,
                    avatarUrl = state.avatarUrl
                )
                startActivity(intent)
            }
            is CallViewModel.CallState.Connecting -> {
                Log.i(TAG, "Connecting call detected on resume - relaunching InCallActivity")
                val intent = InCallActivity.createIntent(
                    context = this,
                    contactId = callViewModel.getRemoteUserId() ?: "",
                    contactName = callViewModel.getRemoteUserName() ?: "Unknown",
                    callId = callViewModel.getRemoteUserId() ?: "",
                    isIncoming = callViewModel.isIncomingCall()
                )
                startActivity(intent)
            }
            else -> {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clockHandler.removeCallbacks(clockRunnable)
        dailyCheckInDialog?.dismiss()
        dailyCheckInDialog = null
        Log.d(TAG, "MainActivity being destroyed")
    }
}
