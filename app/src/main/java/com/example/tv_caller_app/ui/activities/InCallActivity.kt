package com.example.tv_caller_app.ui.activities

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import android.view.WindowManager
import android.view.Window
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.FragmentActivity
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.ui.util.AvatarHelper
import com.example.tv_caller_app.ui.util.MicrophoneStatusHandler
import com.example.tv_caller_app.ui.viewmodel.CallViewModel
import org.webrtc.SurfaceViewRenderer

class InCallActivity : FragmentActivity() {

    private lateinit var txtContactName: TextView
    private lateinit var txtCallStatus: TextView
    private lateinit var txtCallTimer: TextView
    private lateinit var txtMuteStatus: TextView
    private lateinit var txtVideoContactName: TextView
    private lateinit var txtVideoTimer: TextView
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var btnHangUp: ImageButton
    private lateinit var btnCamera: ImageButton
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var txtMuteLabel: TextView
    private lateinit var txtSpeakerLabel: TextView
    private lateinit var txtCameraLabel: TextView
    private lateinit var switchCameraControl: View
    private lateinit var imgContactAvatar: ImageView
    private lateinit var remoteVideoSurface: SurfaceViewRenderer
    private lateinit var localVideoSurface: SurfaceViewRenderer
    private lateinit var callInfoSection: android.widget.LinearLayout
    private lateinit var callControls: View

    private lateinit var callViewModel: CallViewModel
    private lateinit var micHandler: MicrophoneStatusHandler

    private var contactId: String? = null
    private var contactName: String? = null
    private var contactNickname: String? = null
    private var contactAvatarUrl: String? = null
    private var callId: String? = null
    private var isIncoming: Boolean = false

    private var renderersInitialized = false
    private var controlsHidden = false
    private var consumeRevealTouch = false
    private var videoUpgradeDialog: Dialog? = null
    private val controlsHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControlsAfterIdle() }

    companion object {
        private const val TAG = "InCallActivity"
        private const val CONTROLS_TIMEOUT_MS = 30_000L
        private const val CONTROLS_ANIMATION_MS = 260L
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_CONTACT_NICKNAME = "contact_nickname"
        const val EXTRA_CONTACT_AVATAR_URL = "contact_avatar_url"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_IS_INCOMING = "is_incoming"

        fun createIntent(
            context: Context,
            contactId: String,
            contactName: String,
            callId: String,
            isIncoming: Boolean = false,
            nickname: String? = null,
            avatarUrl: String? = null
        ): Intent {
            return Intent(context, InCallActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_CONTACT_NICKNAME, nickname)
                putExtra(EXTRA_CONTACT_AVATAR_URL, avatarUrl)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(window.decorView, getString(R.string.screen_in_call))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                callViewModel.endCall()
            }
        })

        setupWindow()

        setContentView(R.layout.activity_in_call)

        val app = application as TVCallerApplication
        val globalCallViewModel = app.getCallViewModel()

        if (globalCallViewModel == null) {
            Log.e(TAG, "Global CallViewModel not initialized!")
            Toast.makeText(this, getString(R.string.call_service_unavailable), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        callViewModel = globalCallViewModel
        micHandler = MicrophoneStatusHandler(this)

        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        extractIntentData()
        initializeViews()
        updateUI()
        setupButtonListeners()
        observeViewModel()
        stopAnyVibration()
        callViewModel.startCallAudio(preferSpeakerForDevice())

        Log.d(TAG, "InCallActivity created - call with: $contactName")
    }

    private fun setupWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun extractIntentData() {
        contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
        contactNickname = intent.getStringExtra(EXTRA_CONTACT_NICKNAME)
        contactAvatarUrl = intent.getStringExtra(EXTRA_CONTACT_AVATAR_URL)
        callId = intent.getStringExtra(EXTRA_CALL_ID)
        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)

        Log.d(TAG, "Call with: $contactName (ID: $contactId, Incoming: $isIncoming)")
    }

    private fun initializeViews() {
        txtContactName = findViewById(R.id.txt_contact_name)
        txtCallStatus = findViewById(R.id.txt_call_status)
        txtCallTimer = findViewById(R.id.txt_call_timer)

        val txtNickname = findViewById<TextView>(R.id.txt_contact_nickname)
        if (!contactNickname.isNullOrBlank()) {
            txtNickname.text = contactNickname
            txtNickname.visibility = View.VISIBLE
        }

        imgContactAvatar = findViewById(R.id.img_contact_avatar)
        val txtAvatarInitial = findViewById<android.widget.TextView>(R.id.txt_avatar_initial)
        AvatarHelper.loadAvatar(imgContactAvatar, contactAvatarUrl, R.drawable.call_avatar_circle, txtAvatarInitial, contactName)

        txtMuteStatus = findViewById(R.id.txt_mute_status)
        txtVideoContactName = findViewById(R.id.txt_video_contact_name)
        txtVideoTimer = findViewById(R.id.txt_video_timer)
        btnMute = findViewById(R.id.btn_mute)
        btnSpeaker = findViewById(R.id.btn_speaker)
        btnHangUp = findViewById(R.id.btn_hang_up)
        btnCamera = findViewById(R.id.btn_camera)
        btnSwitchCamera = findViewById(R.id.btn_switch_camera)
        txtMuteLabel = findViewById(R.id.txt_mute_label)
        txtSpeakerLabel = findViewById(R.id.txt_speaker_label)
        txtCameraLabel = findViewById(R.id.txt_camera_label)
        switchCameraControl = findViewById(R.id.switch_camera_control)
        remoteVideoSurface = findViewById(R.id.remote_video_surface)
        localVideoSurface = findViewById(R.id.local_video_surface)
        callInfoSection = findViewById(R.id.call_info_section)
        callControls = findViewById(R.id.call_controls)

        initializeVideoRenderers()

        val hasCamera = callViewModel.hasCamera()
        if (!hasCamera) {
            localVideoSurface.visibility = View.GONE
            btnCamera.setImageResource(R.drawable.ic_camera_off)
            btnCamera.contentDescription = getString(R.string.content_desc_camera_off)
            btnCamera.isEnabled = false
            btnCamera.alpha = 0.4f
        }
        updateSwitchCameraState(callViewModel.isCameraEnabled.value == true)

        btnHangUp.requestFocus()
    }

    private fun initializeVideoRenderers() {
        try {
            callViewModel.initVideoRenderers(localVideoSurface, remoteVideoSurface)
            renderersInitialized = true
            Log.d(TAG, "Video renderers initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize video renderers", e)
        }
    }

    private fun updateUI() {
        val name = contactName ?: getString(R.string.unknown_contact)
        txtContactName.text = name
        txtVideoContactName.text = name
        txtCallStatus.text = getString(
            if (callViewModel.callState.value is CallViewModel.CallState.Connecting) {
                R.string.status_connecting
            } else {
                R.string.status_connected
            }
        )
        txtCallTimer.text = getString(R.string.call_timer_zero)
    }

    private fun setupButtonListeners() {
        btnMute.setOnClickListener {
            showControlsAndScheduleAutoHide()
            Log.d(TAG, "Mute button clicked")
            callViewModel.toggleMute()
        }

        btnSpeaker.setOnClickListener {
            showControlsAndScheduleAutoHide()
            Log.d(TAG, "Speaker button clicked")
            callViewModel.toggleSpeaker()
        }

        btnHangUp.setOnClickListener {
            controlsHandler.removeCallbacks(hideControlsRunnable)
            Log.d(TAG, "Hang up button clicked")
            callViewModel.endCall()
        }

        btnCamera.setOnClickListener {
            showControlsAndScheduleAutoHide()
            Log.d(TAG, "Camera toggle clicked")
            callViewModel.toggleCamera()
        }


        btnSwitchCamera.setOnClickListener {
            showControlsAndScheduleAutoHide()
            Log.d(TAG, "Switch camera clicked")
            callViewModel.switchCamera()
        }
    }

    private fun observeViewModel() {
        callViewModel.callState.observe(this) { state ->
            Log.d(TAG, "Call state changed: $state")

            when (state) {
                is CallViewModel.CallState.Connecting -> {
                    txtCallStatus.text = getString(R.string.status_connecting)
                }
                is CallViewModel.CallState.Connected -> {
                    txtCallStatus.text = getString(R.string.status_connected)
                }
                is CallViewModel.CallState.Ended -> {
                    Log.i(TAG, "Call ended: ${state.reason}")
                    txtCallStatus.text = getString(R.string.call_ended)

                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                }
                is CallViewModel.CallState.Failed -> {
                    Log.e(TAG, "Call failed: ${state.error}")
                    txtCallStatus.text = getString(R.string.call_failed)

                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                }
                else -> {
                    Log.d(TAG, "State in InCallActivity: $state")
                }
            }
        }

        callViewModel.callDuration.observe(this) { duration ->
            val minutes = duration / 60
            val seconds = duration % 60
            val formatted = String.format(Locale.US, "%02d:%02d", minutes, seconds)
            txtCallTimer.text = formatted
            txtVideoTimer.text = formatted
        }

        callViewModel.isMuted.observe(this) { isMuted ->
            updateMuteButton(isMuted)
        }

        callViewModel.isSpeakerOn.observe(this) { isSpeakerOn ->
            updateSpeakerButton(isSpeakerOn)
        }

        callViewModel.isCameraEnabled.observe(this) { isEnabled ->
            updateCameraButton(isEnabled)
            updateSwitchCameraState(isEnabled)
            if (!isEnabled) {
                detachLocalVideo()
            } else {
                callViewModel.attachLocalVideo(localVideoSurface)
                localVideoSurface.visibility = View.VISIBLE
            }
            updateControlsAutoHide()
        }

        callViewModel.isFrontCamera.observe(this) { isFrontFacing ->
            localVideoSurface.setMirror(isFrontFacing)
        }

        callViewModel.isLocalVideoActive.observe(this) { isActive ->
            if (!callViewModel.hasCamera()) return@observe
            if (isActive) {
                callViewModel.attachLocalVideo(localVideoSurface)
                localVideoSurface.visibility = View.VISIBLE
            } else {
                detachLocalVideo()
            }
        }

        callViewModel.isRemoteVideoActive.observe(this) { isActive ->
            if (isActive) {
                callViewModel.attachRemoteVideo(remoteVideoSurface)
                remoteVideoSurface.visibility = View.VISIBLE
                callInfoSection.visibility = View.GONE
                txtVideoContactName.visibility = View.VISIBLE
                txtVideoTimer.visibility = View.VISIBLE
            } else {
                detachRemoteVideo()
            }
            updateControlsAutoHide()
        }

        callViewModel.videoUpgradeRequested.observe(this) { requested ->
            if (requested == true) showVideoUpgradeDialog() else dismissVideoUpgradeDialog()
        }

        callViewModel.videoUpgradeWaiting.observe(this) { waiting ->
            btnCamera.isEnabled = !waiting && callViewModel.hasCamera()
            btnCamera.alpha = if (waiting) 0.55f else if (callViewModel.hasCamera()) 1f else 0.4f
            if (waiting) txtCameraLabel.setText(R.string.video_upgrade_waiting)
            else updateCameraButton(callViewModel.isCameraEnabled.value == true)
        }

        callViewModel.errorMessage.observe(this) { error ->
            error?.let {
                Log.e(TAG, "Error: $it")
                txtCallStatus.text = getString(R.string.error_prefix, it)
            }
        }

        var lastMicMessage = ""
        callViewModel.microphoneModeMessage.observe(this) { message ->
            lastMicMessage = message
        }

        callViewModel.isMicrophoneAvailable.observe(this) { isMicAvailable ->
            Log.d(TAG, "Microphone available: $isMicAvailable")
            val isTv = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            if (isTv) micHandler.handleModeChange(isMicAvailable, lastMicMessage)
            btnMute.isEnabled = isMicAvailable
            btnMute.alpha = if (isMicAvailable) 1.0f else 0.5f
        }

    }

    private fun detachLocalVideo() {
        try {
            callViewModel.detachLocalVideo(localVideoSurface)
            localVideoSurface.clearImage()
            localVideoSurface.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach local video", e)
        }
    }

    private fun detachRemoteVideo() {
        try {
            callViewModel.detachRemoteVideo(remoteVideoSurface)
            remoteVideoSurface.clearImage()
            remoteVideoSurface.visibility = View.GONE
            callInfoSection.visibility = View.VISIBLE
            txtVideoContactName.visibility = View.GONE
            txtVideoTimer.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach remote video", e)
        }
    }

    private fun showVideoUpgradeDialog() {
        if (videoUpgradeDialog?.isShowing == true || isFinishing || isDestroyed) return
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_logout_confirm)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        dialog.findViewById<TextView>(R.id.dialog_icon).text = "🎥"
        dialog.findViewById<TextView>(R.id.dialog_title).setText(R.string.video_upgrade_title)
        dialog.findViewById<TextView>(R.id.dialog_message).text = getString(
            R.string.video_upgrade_message,
            contactName ?: getString(R.string.unknown_contact)
        )
        val accept = dialog.findViewById<TextView>(R.id.btn_confirm)
        val keepAudio = dialog.findViewById<TextView>(R.id.btn_cancel)
        accept.setText(R.string.video_upgrade_accept)
        keepAudio.setText(R.string.video_upgrade_keep_audio)
        accept.setOnClickListener {
            callViewModel.respondToVideoUpgrade(true)
            dialog.dismiss()
        }
        keepAudio.setOnClickListener {
            callViewModel.respondToVideoUpgrade(false)
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            if (videoUpgradeDialog === dialog) videoUpgradeDialog = null
        }
        videoUpgradeDialog = dialog
        dialog.show()
        val maxWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val preferred = resources.getDimensionPixelSize(R.dimen.dialog_width)
        dialog.window?.setLayout(preferred.coerceAtMost(maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
        keepAudio.requestFocus()
    }

    private fun dismissVideoUpgradeDialog() {
        videoUpgradeDialog?.dismiss()
        videoUpgradeDialog = null
    }

    private fun stopAnyVibration() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.cancel()
            Log.d(TAG, "Stopped any lingering vibration")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop vibration", e)
        }
    }

    private fun updateMuteButton(isMuted: Boolean) {
        if (isMuted) {
            btnMute.setImageResource(R.drawable.ic_mic_off)
            txtMuteStatus.visibility = View.VISIBLE
            txtMuteStatus.text = getString(R.string.muted)
        } else {
            btnMute.setImageResource(R.drawable.ic_mic)
            txtMuteStatus.visibility = View.GONE
        }
        txtMuteLabel.text = getString(if (isMuted) R.string.label_unmute else R.string.label_mute)
        ViewCompat.setStateDescription(btnMute, getString(if (isMuted) R.string.setting_on else R.string.setting_off))
    }

    private fun updateSpeakerButton(isSpeakerOn: Boolean) {
        btnSpeaker.setImageResource(if (isSpeakerOn) R.drawable.ic_speaker_on else R.drawable.ic_speaker)
        txtSpeakerLabel.setText(R.string.label_speaker)
        ViewCompat.setStateDescription(
            btnSpeaker,
            getString(if (isSpeakerOn) R.string.setting_on else R.string.setting_off)
        )
    }

    private fun updateCameraButton(isEnabled: Boolean) {
        if (isEnabled) {
            btnCamera.setImageResource(R.drawable.ic_camera_on)
            btnCamera.contentDescription = getString(R.string.content_desc_camera_on)
        } else {
            btnCamera.setImageResource(R.drawable.ic_camera_off)
            btnCamera.contentDescription = getString(R.string.content_desc_camera_off)
        }
        txtCameraLabel.text = getString(if (isEnabled) R.string.label_stop_video else R.string.label_start_video)
        ViewCompat.setStateDescription(btnCamera, getString(if (isEnabled) R.string.setting_on else R.string.setting_off))
    }

    private fun updateSwitchCameraState(videoEnabled: Boolean) {
        if (!this::btnSwitchCamera.isInitialized) return
        val showSwitchCamera = videoEnabled && callViewModel.hasMultipleCameras()
        if (!showSwitchCamera && btnSwitchCamera.hasFocus()) {
            btnCamera.requestFocus()
        }
        switchCameraControl.visibility = if (showSwitchCamera) View.VISIBLE else View.GONE
        btnSwitchCamera.isEnabled = showSwitchCamera
        btnCamera.nextFocusRightId = if (showSwitchCamera) R.id.btn_switch_camera else R.id.btn_camera
    }

    private fun preferSpeakerForDevice(): Boolean {
        val isTv = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        return isTv || resources.configuration.smallestScreenWidthDp >= 600
    }

    private fun updateControlsAutoHide() {
        showControlsAndScheduleAutoHide()
    }

    private fun showControlsAndScheduleAutoHide(restoreRemoteFocus: Boolean = false) {
        showControls(restoreRemoteFocus)
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    private fun showControls(restoreRemoteFocus: Boolean) {
        if (controlsHidden) {
            controlsHidden = false
            callControls.visibility = View.VISIBLE
            callControls.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(CONTROLS_ANIMATION_MS)
                .start()
            if (restoreRemoteFocus) btnHangUp.requestFocus()
        } else {
            callControls.alpha = 1f
        }
    }

    private fun hideControlsAfterIdle() {
        if (isFinishing || isDestroyed) return
        controlsHidden = true
        callControls.animate()
            .alpha(0f)
            .translationY(callControls.height * 0.22f)
            .setDuration(CONTROLS_ANIMATION_MS)
            .withEndAction {
                if (controlsHidden) callControls.visibility = View.GONE
            }
            .start()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!this::callControls.isInitialized || !this::callViewModel.isInitialized) {
            return super.dispatchTouchEvent(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && controlsHidden) {
            consumeRevealTouch = true
            showControlsAndScheduleAutoHide()
            return true
        }
        if (consumeRevealTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumeRevealTouch = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) showControlsAndScheduleAutoHide()
        return super.dispatchTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!this::callControls.isInitialized || !this::callViewModel.isInitialized) {
            return super.onKeyDown(keyCode, event)
        }
        if (controlsHidden) {
            showControlsAndScheduleAutoHide(restoreRemoteFocus = true)
            return true
        }
        showControlsAndScheduleAutoHide()
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        dismissVideoUpgradeDialog()
        super.onDestroy()

        detachLocalVideo()
        detachRemoteVideo()

        if (renderersInitialized) {
            try {
                localVideoSurface.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release local video surface", e)
            }
            try {
                remoteVideoSurface.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release remote video surface", e)
            }
        }

        callViewModel.stopCallAudio()

        Log.d(TAG, "InCallActivity destroyed")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        micHandler.handlePermissionResult(
            requestCode = requestCode,
            permissions = permissions,
            grantResults = grantResults,
            onGranted = { Log.d(TAG, "Permission granted during call") },
            onDenied  = { Log.w(TAG, "Permission denied during call — staying in receive-only mode") }
        )
    }
}
