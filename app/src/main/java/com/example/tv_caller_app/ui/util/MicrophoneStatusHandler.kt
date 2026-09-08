package com.example.tv_caller_app.ui.util

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.example.tv_caller_app.calling.permissions.PermissionHelper

class MicrophoneStatusHandler(private val activity: Activity) {

    companion object {
        private const val TAG = "MicrophoneStatusHandler"
    }

    private val context: Context = activity.applicationContext

    fun checkAndRequestPermission(
        onGranted: () -> Unit,
        onDenied: () -> Unit,
        showRationale: Boolean = true
    ) {
        if (PermissionHelper.hasAudioPermission(context)) {
            Log.d(TAG, "Audio permission already granted")
            if (PermissionHelper.needsBluetoothPermission() &&
                !PermissionHelper.hasBluetoothPermission(context)) {
                Log.d(TAG, "Requesting Bluetooth permissions for mic support (Android 12+)")
                ActivityCompat.requestPermissions(
                    activity,
                    PermissionHelper.getBluetoothPermissions(),
                    PermissionHelper.REQUEST_CODE_BLUETOOTH_PERMISSION
                )
            }
            onGranted()
            return
        }

        if (showRationale && PermissionHelper.shouldShowAudioRationale(activity)) {
            showPermissionRationaleDialog(
                onAccept = { PermissionHelper.requestAudioPermission(activity) },
                onDecline = onDenied
            )
        } else {
            PermissionHelper.requestAudioPermission(activity)
        }
    }

    fun handlePermissionResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        if (requestCode == PermissionHelper.REQUEST_CODE_BLUETOOTH_PERMISSION) {
            if (PermissionHelper.hasBluetoothPermission(context)) {
                Log.d(TAG, "Bluetooth permissions granted — Bluetooth mic fully supported")
            } else {
                Log.w(TAG, "Bluetooth permissions denied — Bluetooth mic may not work")
                showToast("Bluetooth permission denied. Bluetooth microphones may not work.")
            }
            return
        }

        PermissionHelper.handleAudioPermissionResult(
            requestCode = requestCode,
            permissions = permissions,
            grantResults = grantResults,
            onGranted = {
                showToast("Microphone permission granted")
                onGranted()
            },
            onDenied = {
                if (PermissionHelper.shouldShowAudioRationale(activity)) {
                    showToast("Microphone permission denied. App will run in receive-only mode.")
                    onDenied()
                } else {
                    showPermanentlyDeniedDialog()
                    onDenied()
                }
            }
        )
    }

    fun handleModeChange(isMicAvailable: Boolean, message: String) {
        Log.i(TAG, "Microphone available: $isMicAvailable - $message")
    }

    private fun showPermissionRationaleDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle("Microphone Permission Needed")
            .setMessage(PermissionHelper.getAudioPermissionExplanation(showExtendedHelp = true))
            .setPositiveButton("Grant Permission") { _, _ -> onAccept() }
            .setNegativeButton("Not Now") { _, _ -> onDecline() }
            .setCancelable(false)
            .show()
    }

    private fun showPermanentlyDeniedDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Permission Required")
            .setMessage(PermissionHelper.getAudioPermanentlyDeniedMessage())
            .setPositiveButton("OK", null)
            .setCancelable(true)
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
