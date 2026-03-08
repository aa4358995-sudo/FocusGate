package com.focusgate.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusgate.FocusGateApplication
import com.focusgate.data.PreferencesManager
import com.focusgate.ui.IntentGateActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * ScreenReceiver — listens for screen unlock events to trigger the Intent Gate.
 */
class ScreenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT -> {
                // User dismissed keyguard — phone is being actively used
                Log.d(TAG, "User present — checking if Intent Gate should appear")
                handleUserPresent(context)
            }
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen ON")
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen OFF — clearing intent gate state")
            }
        }
    }

    private fun handleUserPresent(context: Context) {
        val prefs = FocusGateApplication.instance.preferencesManager

        runBlocking {
            val isGateEnabled = prefs.isGateEnabled.first()
            val isWorkModeActive = prefs.isWorkModeActive.first()

            if (!isGateEnabled) {
                Log.d(TAG, "Gate disabled — skipping")
                return@runBlocking
            }

            // Work Mode manages its own experience, no gate needed
            if (isWorkModeActive) {
                Log.d(TAG, "Work Mode active — gate skipped")
                return@runBlocking
            }

            // Check if current intent is still valid
            val currentIntent = prefs.currentIntent.first()
            val intentTimestamp = prefs.intentTimestamp.first()
            val validityMinutes = prefs.intentValidityMinutes.first()
            val now = System.currentTimeMillis()
            val intentAge = (now - intentTimestamp) / 60_000

            if (currentIntent.isNotBlank() && intentAge < validityMinutes) {
                Log.d(TAG, "Intent '$currentIntent' still valid (${intentAge}m old) — gate skipped")
                return@runBlocking
            }

            // Launch the Intent Gate
            Log.i(TAG, "Launching Intent Gate")
            val gateIntent = Intent(context, IntentGateActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(gateIntent)
        }
    }
}
