package com.focusgate.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.focusgate.FocusGateApplication
import com.focusgate.data.PreferencesManager
import com.focusgate.services.AppMonitorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * BootReceiver — called when the device finishes booting.
 * Restarts FocusGate's background services and cleans up stale Work Mode state.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.i(TAG, "Device booted — restarting FocusGate services")

        val prefs = FocusGateApplication.instance.preferencesManager

        runBlocking {
            // Clean up any stale Work Mode (it couldn't persist through a reboot meaningfully)
            val workModeActive = prefs.isWorkModeActive.first()
            val workModeEndTime = prefs.workModeEndTime.first()
            val now = System.currentTimeMillis()

            if (workModeActive && (workModeEndTime == 0L || now > workModeEndTime)) {
                Log.i(TAG, "Cleaning up stale Work Mode after reboot")
                prefs.endWorkMode()
            }
        }

        // Start the monitor service
        val serviceIntent = Intent(context, AppMonitorService::class.java)
        context.startForegroundService(serviceIntent)
    }
}
