package com.focusgate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.focusgate.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * FocusGate Application class.
 * Initializes global dependencies, notification channels, and app-wide state.
 */
class FocusGateApplication : Application() {

    // Application-scoped coroutine scope — survives activity lifecycle
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var preferencesManager: PreferencesManager
        private set

    companion object {
        lateinit var instance: FocusGateApplication
            private set

        // Notification Channel IDs
        const val CHANNEL_MONITOR_SERVICE = "focus_monitor_service"
        const val CHANNEL_NUDGE_ALERTS = "focus_nudge_alerts"
        const val CHANNEL_DIGEST = "focus_digest"
        const val CHANNEL_WORK_MODE = "focus_work_mode"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesManager = PreferencesManager(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Silent foreground service channel
            NotificationChannel(
                CHANNEL_MONITOR_SERVICE,
                "FocusGate Monitor",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps FocusGate running in the background"
                setShowBadge(false)
                manager.createNotificationChannel(this)
            }

            // Nudge alerts
            NotificationChannel(
                CHANNEL_NUDGE_ALERTS,
                "Awareness Nudges",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Gentle reminders about your stated intent"
                enableVibration(true)
                manager.createNotificationChannel(this)
            }

            // Post-focus digest
            NotificationChannel(
                CHANNEL_DIGEST,
                "Focus Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Summary of what you missed during focus mode"
                manager.createNotificationChannel(this)
            }

            // Work mode status
            NotificationChannel(
                CHANNEL_WORK_MODE,
                "Work Mode Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current work mode status"
                setShowBadge(false)
                manager.createNotificationChannel(this)
            }
        }
    }
}
