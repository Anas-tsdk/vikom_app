package com.example.tv_caller_app.calling.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.example.tv_caller_app.TVCallerApplication

class ScreenOffReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenOffReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "TV screen turned off — ending call and going offline")
                handleTvOff(context)
            }
            Intent.ACTION_SHUTDOWN -> {
                Log.i(TAG, "TV shutting down — ending call and going offline")
                handleTvOff(context)
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "TV screen turned on — restoring online state")
                handleTvOn(context)
            }
        }
    }

    private fun handleTvOff(context: Context) {
        if (!isTv(context)) {
            Log.d(TAG, "Screen off ignored — not a TV device")
            return
        }
        val app = context.applicationContext as? TVCallerApplication ?: return
        app.getCallViewModel()?.endCall()
        app.stopPresence()
    }

    private fun handleTvOn(context: Context) {
        if (!isTv(context)) return
        val app = context.applicationContext as? TVCallerApplication ?: return
        app.handleScreenOnAndReconnect(context)
    }

    private fun isTv(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}
