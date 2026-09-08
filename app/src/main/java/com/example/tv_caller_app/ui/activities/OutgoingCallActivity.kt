package com.example.tv_caller_app.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import com.example.tv_caller_app.R
import com.example.tv_caller_app.TVCallerApplication
import com.example.tv_caller_app.ui.util.AvatarHelper
import com.example.tv_caller_app.ui.viewmodel.CallViewModel

class OutgoingCallActivity : FragmentActivity() {


    private lateinit var txtContactName: TextView
    private lateinit var txtContactPhone: TextView
    private lateinit var txtCallStatus: TextView
    private lateinit var btnHangUp: ImageButton

    private lateinit var callViewModel: CallViewModel

    private var contactId: String? = null
    private var contactName: String? = null
    private var contactNickname: String? = null
    private var contactPhone: String? = null
    private var contactAvatarUrl: String? = null
    private var callId: String? = null
    private var callType: String = "audio"

    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val callTimeoutMs = CallViewModel.RINGING_TIMEOUT_MS

    companion object {
        private const val TAG = "OutgoingCallActivity"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val EXTRA_CONTACT_NICKNAME = "contact_nickname"
        const val EXTRA_CONTACT_PHONE = "contact_phone"
        const val EXTRA_CONTACT_AVATAR_URL = "contact_avatar_url"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALL_TYPE = "call_type"

        fun createIntent(
            context: Context,
            contactId: String,
            contactName: String,
            callId: String,
            contactPhone: String? = null,
            nickname: String? = null,
            avatarUrl: String? = null,
            callType: String = "audio"
        ): Intent {
            return Intent(context, OutgoingCallActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_CONTACT_NICKNAME, nickname)
                putExtra(EXTRA_CONTACT_PHONE, contactPhone)
                putExtra(EXTRA_CONTACT_AVATAR_URL, avatarUrl)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALL_TYPE, callType)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewCompat.setAccessibilityPaneTitle(window.decorView, getString(R.string.screen_outgoing_call))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelCall()
            }
        })

        setupWindow()

        setContentView(R.layout.activity_outgoing_call)

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
        startRingbackTone()
        startTimeoutTimer()

        Log.d(TAG, "OutgoingCallActivity created for contact: $contactName")
    }

    private fun setupWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun extractIntentData() {
        contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
        contactNickname = intent.getStringExtra(EXTRA_CONTACT_NICKNAME)
        contactPhone = intent.getStringExtra(EXTRA_CONTACT_PHONE)
        contactAvatarUrl = intent.getStringExtra(EXTRA_CONTACT_AVATAR_URL)
        callId = intent.getStringExtra(EXTRA_CALL_ID)
        callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "audio"

        Log.d(TAG, "Calling: $contactName (ID: $contactId), type=$callType")
    }

    private fun initializeViews() {
        txtContactName = findViewById(R.id.txt_contact_name)
        txtContactPhone = findViewById(R.id.txt_contact_phone)
        txtCallStatus = findViewById(R.id.txt_call_status)

        val txtNickname = findViewById<TextView>(R.id.txt_contact_nickname)
        if (!contactNickname.isNullOrBlank()) {
            txtNickname.text = contactNickname
            txtNickname.visibility = android.view.View.VISIBLE
        }

        val imgAvatar = findViewById<ImageView>(R.id.img_contact_avatar)
        val txtAvatarInitial = findViewById<android.widget.TextView>(R.id.txt_avatar_initial)
        AvatarHelper.loadAvatar(imgAvatar, contactAvatarUrl, R.drawable.call_avatar_circle, txtAvatarInitial, contactName)

        btnHangUp = findViewById(R.id.btn_hang_up)

        btnHangUp.requestFocus()
    }

    private fun updateUI() {
        txtContactName.text = contactName ?: getString(R.string.unknown_contact)

        if (!contactPhone.isNullOrEmpty()) {
            txtContactPhone.text = contactPhone
            txtContactPhone.visibility = android.view.View.VISIBLE
        }

        txtCallStatus.text = if (callType == "video") getString(R.string.outgoing_video_call) else getString(R.string.outgoing_audio_call)
    }

    private fun setupButtonListeners() {
        btnHangUp.setOnClickListener {
            Log.d(TAG, "Hang up button clicked")
            cancelCall()
        }
    }

    private fun observeViewModel() {
        callViewModel.callState.observe(this) { state ->
            Log.d(TAG, "Call state changed: $state")

            when (state) {
                is CallViewModel.CallState.Connected -> {
                    Log.i(TAG, "Call answered - transitioning to InCallActivity")

                    timeoutHandler.removeCallbacksAndMessages(null)
                    stopRingbackTone()

                    val intent = InCallActivity.createIntent(
                        context = this,
                        contactId = state.remoteUserId,
                        contactName = state.remoteUserName,
                        callId = callId ?: "",
                        isIncoming = false,
                        nickname = contactNickname,
                        avatarUrl = contactAvatarUrl
                    )
                    startActivity(intent)
                    finish()
                }
                is CallViewModel.CallState.Ended -> {
                    Log.i(TAG, "Call ended: ${state.reason}")
                    stopRingbackTone()
                    timeoutHandler.removeCallbacksAndMessages(null)
                    txtCallStatus.text = when (state.reason) {
                        "rejected" -> getString(R.string.call_rejected)
                        "user_hangup" -> getString(R.string.call_cancelled)
                        else -> getString(R.string.call_ended)
                    }
                    timeoutHandler.postDelayed({ finish() }, 2000)
                }
                is CallViewModel.CallState.Failed -> {
                    Log.e(TAG, "Call failed: ${state.error}")
                    stopRingbackTone()
                    timeoutHandler.removeCallbacksAndMessages(null)
                    txtCallStatus.text = getString(R.string.call_failed)
                    timeoutHandler.postDelayed({ finish() }, 2000)
                }
                else -> {
                    Log.d(TAG, "Unhandled state in OutgoingCallActivity: $state")
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

    private fun cancelCall() {
        Log.i(TAG, "Canceling call to: $contactName")

        stopRingbackTone()
        timeoutHandler.removeCallbacksAndMessages(null)
        callViewModel.endCall()
        finish()
    }

    private fun startRingbackTone() {
        callViewModel.startCallAudio()
        Log.d(TAG, "Ringback tone started (placeholder)")
    }

    private fun stopRingbackTone() {
        callViewModel.stopCallAudio()
        Log.d(TAG, "Ringback tone stopped, audio mode reset")
    }

    private fun startTimeoutTimer() {
        timeoutHandler.postDelayed({
            Log.w(TAG, "Call timeout - no answer after ${callTimeoutMs / 1000} seconds")
            onCallTimeout()
        }, callTimeoutMs)
    }

    private fun onCallTimeout() {
        txtCallStatus.text = getString(R.string.no_answer)

        stopRingbackTone()
        callViewModel.endCall()

        timeoutHandler.postDelayed({
            finish()
        }, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()

        stopRingbackTone()
        timeoutHandler.removeCallbacksAndMessages(null)

        Log.d(TAG, "OutgoingCallActivity destroyed")
    }

}
