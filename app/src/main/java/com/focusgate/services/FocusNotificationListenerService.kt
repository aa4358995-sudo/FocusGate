package com.focusgate.services

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import com.focusgate.FocusGateApplication
import com.focusgate.data.CapturedNotification
import com.focusgate.data.FocusRepository
import com.focusgate.data.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * FocusNotificationListenerService
 *
 * During Work Mode: intercepts all notifications, suppresses them visually,
 * and stores them in the database for the Post-Focus Digest.
 *
 * Calls are ALWAYS passed through — never blocked.
 */
class FocusNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "FocusNotifListener"

        // Packages that should NEVER be suppressed
        private val CALL_PACKAGES = setOf(
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.incallui",
            "com.android.server.telecom"
        )

        // Emergency / system packages
        private val SYSTEM_EXEMPT = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.gms"
        )

        @Volatile var instance: FocusNotificationListenerService? = null
            private set

        @Volatile var isCapturing = false
            private set

        var activeSessionId = ""

        fun startCapturing(sessionId: String) {
            activeSessionId = sessionId
            isCapturing = true
            Log.i(TAG, "Started capturing notifications for session: $sessionId")
        }

        fun stopCapturing(): String {
            val sessionId = activeSessionId
            isCapturing = false
            activeSessionId = ""
            Log.i(TAG, "Stopped capturing notifications")
            return sessionId
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: FocusRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = FocusGateApplication.instance.preferencesManager
        repository = FocusRepository(this)
        Log.i(TAG, "NotificationListenerService created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName
        val notification = sbn.notification ?: return

        // Always pass through calls and emergency
        if (CALL_PACKAGES.contains(packageName) || SYSTEM_EXEMPT.contains(packageName)) {
            Log.d(TAG, "Passing through exempt notification from $packageName")
            return
        }

        // Suppress and capture during Work Mode
        if (isCapturing && activeSessionId.isNotBlank()) {
            captureAndSuppressNotification(sbn, packageName, notification)
        }
    }

    private fun captureAndSuppressNotification(
        sbn: StatusBarNotification,
        packageName: String,
        notification: Notification
    ) {
        serviceScope.launch {
            try {
                val extras = notification.extras
                val title = extras?.getString(Notification.EXTRA_TITLE) ?: ""
                val text = extras?.getString(Notification.EXTRA_TEXT)
                    ?: extras?.getString(Notification.EXTRA_BIG_TEXT)
                    ?: ""

                if (title.isBlank() && text.isBlank()) return@launch

                val appLabel = getAppLabel(packageName)
                val isCall = CALL_PACKAGES.contains(packageName) ||
                        notification.category == Notification.CATEGORY_CALL

                val captured = CapturedNotification(
                    sessionId = activeSessionId,
                    packageName = packageName,
                    appName = appLabel,
                    title = title,
                    text = text.take(200),
                    timestamp = sbn.postTime,
                    importance = notification.priority,
                    isCall = isCall
                )

                repository.captureNotification(captured)

                // Cancel the notification to prevent it from appearing
                if (!isCall) {
                    try {
                        cancelNotification(sbn.key)
                        Log.d(TAG, "Suppressed notification from $packageName: $title")
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not cancel notification: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing notification: ${e.message}")
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // We don't need to handle this
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification listener disconnected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
    }
}
