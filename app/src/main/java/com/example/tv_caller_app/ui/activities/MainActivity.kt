package com.example.tv_caller_app.ui.activities

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.drawable.toDrawable
import com.example.tv_caller_app.R
import com.example.tv_caller_app.ui.fragments.AIFragment
import com.example.tv_caller_app.ui.fragments.HamburgerMenuFragment
import com.example.tv_caller_app.ui.fragments.ProfileFragment
import com.example.tv_caller_app.ui.fragments.QuickDialFragment
import com.example.tv_caller_app.ui.fragments.SettingsFragment
import com.example.tv_caller_app.settings.SettingsManager
import com.example.tv_caller_app.ui.viewmodel.AuthViewModel
import com.example.tv_caller_app.ui.viewmodel.CallViewModel

class MainActivity : FragmentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var headerBar: RelativeLayout
    private lateinit var txtWelcome: TextView
    private lateinit var txtClockTime: TextView
    private lateinit var txtClockDate: TextView
    private lateinit var authViewModel: AuthViewModel
    private var hasCheckedOnboarding = false
    private var observedCallViewModel: CallViewModel? = null

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

        observeAuthViewModel(savedInstanceState)
        observeSessionExpiry()
        setupHamburgerMenu()
        setupProfileButton()
        setupSettingsButton()
        applyMenuLabelVisibility()
        updateClock()
        clockHandler.postDelayed(clockRunnable, 30000)
        authViewModel.checkSession()
        setupAiAssistantButton()
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
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, AIFragment())
                .addToBackStack(null)
                .commit()
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
                .replace(R.id.main_browse_fragment, QuickDialFragment())
                .commitNow()
        }
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
                    Log.d(TAG, "Hamburger menu clicked")
                    HamburgerMenuFragment().show(supportFragmentManager, "hamburger_menu")
                }
            }
        }
    }

    private fun setupProfileButton() {
        findViewById<LinearLayout>(R.id.btn_profile)?.setOnClickListener {
            Log.d(TAG, "Profile button clicked")
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, ProfileFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun setupSettingsButton() {
        findViewById<LinearLayout>(R.id.btn_settings)?.setOnClickListener {
            Log.d(TAG, "Settings button clicked")
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, SettingsFragment())
                .addToBackStack(null)
                .commit()
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
                    authViewModel.cachedUsername.observe(this) { username ->
                        if (!username.isNullOrBlank()) {
                            txtWelcome.text = "Hei, $username!"
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
                                        startActivity(OnboardingActivity.createIntent(this@MainActivity))
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
                    loadMainContent(savedInstanceState)
                }
                false -> {
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
            title = "Utloggingsbekreftelse",
            message = "Er du sikker på at du vil logge ut ?",
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
        authViewModel.logout()
    }

    fun showHeader() {
        headerBar.visibility = View.VISIBLE
    }

    fun hideHeader() {
        headerBar.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        checkForActiveCall()
        observeIncomingCalls()
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
        Log.d(TAG, "MainActivity being destroyed")
    }
}
