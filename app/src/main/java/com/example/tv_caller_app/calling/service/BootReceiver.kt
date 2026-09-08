package com.example.tv_caller_app.calling.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tv_caller_app.auth.SessionManager
import com.example.tv_caller_app.ui.activities.MainActivity

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

        Log.i(TAG, "Boot completed — launching app and starting SignalingForegroundService")
        SignalingForegroundService.startService(context)
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(launchIntent)
    }
}
