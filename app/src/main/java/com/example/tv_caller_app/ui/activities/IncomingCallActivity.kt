package com.example.tv_caller_app.ui.activities

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.calling.CallIntentExtras
import com.example.tv_caller_app.settings.SettingsManager
import com.example.tv_caller_app.ui.util.AvatarHelper
import com.example.tv_caller_app.ui.viewmodel.CallViewModel

class IncomingCallActivity : FragmentActivity() {


    private lateinit var txtCallerName: TextView
    private lateinit var txtCallerPhone: TextView
    private lateinit var txtCallStatus: TextView
    private lateinit var btnAnswerCall: ImageButton
    private lateinit var btnAnswerVideoCall: ImageButton
    private lateinit var btnRejectCall: ImageButton
    private lateinit var btnAnswerVideoContainer: android.view.View

    private lateinit var callViewModel: CallViewModel

    private var callerId: String? = null
    private var callerName: String? = null
    private var callerNickname: String? = null
    private var callerPhone: String? = null
    private var callerAvatarUrl: String? = null
    private var callId: String? = null
    private var callType: String = "audio"

    private var hasSeenActiveState = false
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var toneHandler: android.os.Handler? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val autoAnswerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingAnswerMediaType: String = "audio"
    private val answerPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val videoAllowed = pendingAnswerMediaType == "video" &&
            result[Manifest.permission.CAMERA] != false &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (pendingAnswerMediaType == "video" && !videoAllowed) {
            Toast.makeText(this, getString(R.string.camera_permission_audio_fallback), Toast.LENGTH_LONG).show()
        }
        completeAnswer(if (videoAllowed) "video" else "audio")
    }

    companion object {
        private const val TAG = "IncomingCallActivity"
        const val EXTRA_CALLER_ID = CallIntentExtras.EXTRA_CALLER_ID
        const val EXTRA_CALLER_NAME = CallIntentExtras.EXTRA_CALLER_NAME
        const val EXTRA_CALLER_NICKNAME = CallIntentExtras.EXTRA_CALLER_NICKNAME
        const val EXTRA_CALLER_PHONE = CallIntentExtras.EXTRA_CALLER_PHONE
        const val EXTRA_CALLER_AVATAR_URL = CallIntentExtras.EXTRA_CALLER_AVATAR_URL
        const val EXTRA_CALL_ID = CallIntentExtras.EXTRA_CALL_ID
        const val EXTRA_CALL_TYPE = CallIntentExtras.EXTRA_CALL_TYPE

        fun createIntent(
            context: Context,
            callerId: String,
            callerName: String,
            callId: String,
            callerPhone: String? = null,
            nickname: String? = null,
            avatarUrl: String? = null,
            callType: String = "audio"
        ): Intent {
            return Intent(context, IncomingCallActivity::class.java).apply {
                putExtra(EXTRA_CALLER_ID, callerId)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_NICKNAME, nickname)
                putExtra(EXTRA_CALLER_PHONE, callerPhone)
                putExtra(EXTRA_CALLER_AVATAR_URL, avatarUrl)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALL_TYPE, callType)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(window.decorView, getString(R.string.screen_incoming_call))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "Back button pressed - ignoring (must answer or reject)")
            }
        })

        acquireWakeLock()
        setupWindow()

        setContentView(R.layout.activity_incoming_call)

        val app = application as TVCallerApplication
        val globalCallViewModel = app.getCallViewModel()

        if (globalCallViewModel == null) {
            Log.e(TAG, "Global CallViewModel not initialized!")
            Toast.makeText(this, getString(R.string.call_service_unavailable), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        callViewModel = globalCallViewModel

        extractIntentData()
        initializeViews()
        updateUI()
        setupButtonListeners()
        observeViewModel()
        startRingtone()
        startVibration()

        if (SettingsManager.getInstance(this).autoAnswer) {
            Log.d(TAG, "Auto-answer enabled - will answer in 2 seconds")
            autoAnswerHandler.postDelayed({ answerCall("audio") }, 2000)
        }

        Log.d(TAG, "IncomingCallActivity created for caller: $callerName (CallViewModel state: ${callViewModel.callState.value})")
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun extractIntentData() {
        callerId = intent.getStringExtra(EXTRA_CALLER_ID)
        callerName = intent.getStringExtra(EXTRA_CALLER_NAME)
        callerNickname = intent.getStringExtra(EXTRA_CALLER_NICKNAME)
        callerPhone = intent.getStringExtra(EXTRA_CALLER_PHONE)
        callerAvatarUrl = intent.getStringExtra(EXTRA_CALLER_AVATAR_URL)
        callId = intent.getStringExtra(EXTRA_CALL_ID)
        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "audio"

        Log.d(TAG, "Incoming call from: $callerName (ID: $callerId), type=$callType")
    }

    private fun initializeViews() {
        txtCallerName = findViewById(R.id.txt_caller_name)
        txtCallerPhone = findViewById(R.id.txt_caller_phone)
        txtCallStatus = findViewById(R.id.txt_call_status)
        btnAnswerCall = findViewById(R.id.btn_answer_call)
        btnAnswerVideoCall = findViewById(R.id.btn_answer_video_call)
        btnAnswerVideoContainer = findViewById(R.id.btn_answer_video_container)
        btnRejectCall = findViewById(R.id.btn_reject_call)

        val txtNickname = findViewById<TextView>(R.id.txt_caller_nickname)
        if (!callerNickname.isNullOrBlank()) {
            txtNickname.text = callerNickname
            txtNickname.visibility = android.view.View.VISIBLE
        }

        val imgAvatar = findViewById<ImageView>(R.id.img_caller_avatar)
        val txtAvatarInitial = findViewById<android.widget.TextView>(R.id.txt_avatar_initial)
        AvatarHelper.loadAvatar(imgAvatar, callerAvatarUrl, R.drawable.call_avatar_circle, txtAvatarInitial, callerName)

        btnAnswerCall.requestFocus()
    }

    private fun updateUI() {
        txtCallerName.text = callerName ?: getString(R.string.unknown_caller)

        if (!callerPhone.isNullOrEmpty()) {
            txtCallerPhone.text = callerPhone
            txtCallerPhone.visibility = android.view.View.VISIBLE
        }

        txtCallStatus.text = if (callType == "video") getString(R.string.incoming_video_call) else getString(R.string.status_ringing)

        if (callType == "video") {
            btnAnswerVideoContainer.visibility = android.view.View.VISIBLE
            findViewById<TextView>(R.id.txt_answer_audio_label)?.text = getString(R.string.answer_audio)
        }
    }

    private fun setupButtonListeners() {
        btnAnswerCall.setOnClickListener {
            Log.d(TAG, "Answer audio button clicked")
            answerCall("audio")
        }

        btnAnswerVideoCall.setOnClickListener {
            Log.d(TAG, "Answer video button clicked")
            answerCall("video")
        }

        btnRejectCall.setOnClickListener {
            Log.d(TAG, "Reject button clicked")
            rejectCall()
        }
    }

    private fun observeViewModel() {
        callViewModel.callState.observe(this) { state ->
            Log.d(TAG, "Call state changed: $state")

            when (state) {
                is CallViewModel.CallState.Connecting -> {
                    Log.i(TAG, "Call connecting - transitioning to InCallActivity")
                    txtCallStatus.text = getString(R.string.status_connecting)

                    stopRingtone()
                    stopVibration()

                    val intent = InCallActivity.createIntent(
                        context = this,
                        contactId = callerId ?: "",
                        contactName = callerName ?: getString(R.string.unknown_caller),
                        callId = callId ?: "",
                        isIncoming = true,
                        nickname = callerNickname,
                        avatarUrl = callerAvatarUrl
                    )
                    startActivity(intent)
                    finish()
                }
                is CallViewModel.CallState.Connected -> {
                    Log.i(TAG, "Call connected")
                    if (!isFinishing) {
                        val intent = InCallActivity.createIntent(
                            context = this,
                            contactId = callerId ?: "",
                            contactName = callerName ?: getString(R.string.unknown_caller),
                            callId = callId ?: "",
                            isIncoming = true,
                            nickname = callerNickname,
                            avatarUrl = callerAvatarUrl
                        )
                        startActivity(intent)
                        finish()
                    }
                }
                is CallViewModel.CallState.Ended -> {
                    Log.i(TAG, "Call ended: ${state.reason}")
                    autoAnswerHandler.removeCallbacksAndMessages(null)
                    stopRingtone()
                    stopVibration()
                    finish()
                }
                is CallViewModel.CallState.Failed -> {
                    Log.e(TAG, "Call failed: ${state.error}")
                    autoAnswerHandler.removeCallbacksAndMessages(null)
                    stopRingtone()
                    stopVibration()
                    txtCallStatus.text = getString(R.string.error_prefix, state.error)
                    txtCallStatus.postDelayed({ finish() }, 2000)
                }
                is CallViewModel.CallState.RingingIncoming -> {
                    hasSeenActiveState = true
                }
                is CallViewModel.CallState.Idle -> {
                    if (hasSeenActiveState) {
                        Log.i(TAG, "Call state went to Idle - stopping ringtone and finishing")
                        autoAnswerHandler.removeCallbacksAndMessages(null)
                        stopRingtone()
                        stopVibration()
                        finish()
                    }
                }
                else -> {
                    Log.d(TAG, "State in IncomingCallActivity: $state")
                }
            }
        }

        callViewModel.errorMessage.observe(this) { error ->
            error?.let {
                Log.e(TAG, "Error: $it")
                txtCallStatus.text = getString(R.string.error_prefix, it)
            }
        }
    }

    private fun answerCall(mediaType: String? = null) {
        if (callViewModel.callState.value !is CallViewModel.CallState.RingingIncoming) {
            Log.w(TAG, "Cannot answer - call is no longer ringing (state: ${callViewModel.callState.value})")
            return
        }

        val requestedType = mediaType ?: callType
        pendingAnswerMediaType = requestedType
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(
                    this@IncomingCallActivity,
                    Manifest.permission.RECORD_AUDIO
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)
            if (requestedType == "video" && ContextCompat.checkSelfPermission(
                    this@IncomingCallActivity,
                    Manifest.permission.CAMERA
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.CAMERA)
        }

        if (missing.isNotEmpty()) {
            answerPermissionLauncher.launch(missing.toTypedArray())
            return
        }

        completeAnswer(requestedType)
    }

    private fun completeAnswer(mediaType: String) {
        if (callViewModel.callState.value !is CallViewModel.CallState.RingingIncoming) return
        Log.i(TAG, "Answering call from: $callerName as $mediaType")

        stopRingtone()
        stopVibration()
        callViewModel.answerCall(mediaType)
    }

    private fun rejectCall() {
        Log.i(TAG, "Rejecting call from: $callerName")

        stopRingtone()
        stopVibration()
        callViewModel.rejectCall()
        finish()
    }

    private fun startRingtone() {
        if (!SettingsManager.getInstance(this).ringtoneEnabled) {
            Log.d(TAG, "Ringtone disabled in settings")
            return
        }

        try {
            val ringtoneUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (ringtoneUri != null) {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(applicationContext, ringtoneUri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
                Log.d(TAG, "Ringtone started (system default)")
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "System ringtone failed, falling back to ToneGenerator", e)
            mediaPlayer?.release()
            mediaPlayer = null
        }

        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneHandler = android.os.Handler(android.os.Looper.getMainLooper())

            val runnable = object : Runnable {
                override fun run() {
                    toneGenerator?.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1000)
                    toneHandler?.postDelayed(this, 2000L)
                }
            }
            runnable.run()
            Log.d(TAG, "Ringtone started (ToneGenerator fallback)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start any ringtone", e)
        }
    }

    private fun startVibration() {
        if (!SettingsManager.getInstance(this).vibrationEnabled) {
            Log.d(TAG, "Vibration disabled in settings")
            return
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }

            val pattern = longArrayOf(0, 1000, 1000)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
            Log.d(TAG, "Vibration started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration", e)
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            vibrator = null

            val freshVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            freshVibrator.cancel()
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop vibration", e)
        }
    }

    private fun stopRingtone() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop MediaPlayer", e)
            mediaPlayer = null
        }

        try {
            toneHandler?.removeCallbacksAndMessages(null)
            toneHandler = null
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop ToneGenerator", e)
            toneHandler = null
            toneGenerator = null
        }

        Log.d(TAG, "Ringtone stopped")
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "TVCaller:IncomingCallWakeLock"
            )
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d(TAG, "Wake lock acquired (SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Wake lock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }

    override fun onStop() {
        super.onStop()
        stopRingtone()
        stopVibration()
    }

    override fun onDestroy() {
        super.onDestroy()

        autoAnswerHandler.removeCallbacksAndMessages(null)
        stopRingtone()
        stopVibration()
        releaseWakeLock()

        Log.d(TAG, "IncomingCallActivity destroyed")
    }

}
