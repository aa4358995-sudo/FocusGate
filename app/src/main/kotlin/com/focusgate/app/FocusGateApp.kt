package com.focusgate.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.focusgate.app.state.FocusStateManager

/**
 * FocusGateApp
 * ────────────
 * Application entry point. Initializes all core singletons,
 * notification channels, and the persistent state manager.
 */
class FocusGateApp : Application() {

    companion object {
        const val CHANNEL_ID_FOCUS_MONITOR = "focus_monitor_service"
        const val CHANNEL_ID_NUDGE = "nudge_alerts"
        const val CHANNEL_ID_DIGEST = "post_focus_digest"

        lateinit var instance: FocusGateApp
            private set
    }

    // Singleton state manager – survives process restarts via DataStore
    val focusStateManager: FocusStateManager by lazy {
        FocusStateManager(applicationContext)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Silent foreground service channel (invisible to user in status bar)
            NotificationChannel(
                CHANNEL_ID_FOCUS_MONITOR,
                "Focus Monitor",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent service keeping FocusGate active"
                setShowBadge(false)
                nm.createNotificationChannel(this)
            }

            // Nudge alerts channel
            NotificationChannel(
                CHANNEL_ID_NUDGE,
                "Intent Nudges",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when you deviate from your stated intent"
                nm.createNotificationChannel(this)
            }

            // Post-focus digest channel
            NotificationChannel(
                CHANNEL_ID_DIGEST,
                "Post-Focus Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Summary of notifications received during Work Mode"
                nm.createNotificationChannel(this)
            }
        }
    }
}
