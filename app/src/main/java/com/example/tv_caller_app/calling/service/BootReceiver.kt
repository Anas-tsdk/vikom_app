package com.example.tv_caller_app.calling.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tv_caller_app.auth.SessionManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val sessionManager = SessionManager.getInstance(context)
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "User not logged in — skipping service start")
            return
        }

        Log.i(TAG, "Boot completed — restoring background call listening")
        SignalingForegroundService.startService(context)
    }
}
