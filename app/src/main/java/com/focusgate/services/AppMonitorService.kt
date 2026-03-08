package com.focusgate.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focusgate.FocusGateApplication
import com.focusgate.R
import com.focusgate.data.PreferencesManager
import com.focusgate.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * AppMonitorService — persistent foreground service.
 *
 * Uses UsageStatsManager to poll the foreground app every 2 seconds.
 * This is a fallback/supplement to the AccessibilityService approach,
 * providing redundancy for foreground app detection.
 */
class AppMonitorService : Service() {

    companion object {
        private const val TAG = "AppMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 2000L

        @Volatile var instance: AppMonitorService? = null
            private set
    }

    inner class LocalBinder : Binder() {
        fun getService(): AppMonitorService = this@AppMonitorService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: PreferencesManager
    private lateinit var usageStatsManager: UsageStatsManager
    private var monitorJob: Job? = null
    private var lastForegroundApp = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = FocusGateApplication.instance.preferencesManager
        usageStatsManager = getSystemService(UsageStatsManager::class.java)
        Log.i(TAG, "AppMonitorService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startMonitoring() {
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val foregroundApp = getCurrentForegroundApp()
                    if (foregroundApp != null && foregroundApp != lastForegroundApp) {
                        lastForegroundApp = foregroundApp
                        // The AccessibilityService handles the logic, but this serves as backup
                        Log.v(TAG, "Foreground: $foregroundApp")
                    }

                    // Check Work Mode expiry
                    val isWorkMode = prefs.isWorkModeActive.first()
                    if (isWorkMode) {
                        val endTime = prefs.workModeEndTime.first()
                        if (endTime > 0 && System.currentTimeMillis() > endTime) {
                            Log.i(TAG, "Work Mode timer expired — ending session")
                            prefs.endWorkMode()
                            updateNotification("Focus session complete! See your digest.")
                        } else if (endTime > 0) {
                            val remaining = (endTime - System.currentTimeMillis()) / 60_000
                            updateNotificationText("Work Mode: ${remaining}m remaining")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Monitor error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun getCurrentForegroundApp(): String? {
        return try {
            val now = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 5000,
                now
            )
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: SecurityException) {
            Log.w(TAG, "No usage stats permission: ${e.message}")
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun buildForegroundNotification(text: String = "Watching over your focus ✦"): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, FocusGateApplication.CHANNEL_MONITOR_SERVICE)
            .setSmallIcon(R.drawable.ic_focus_gate)
            .setContentTitle("FocusGate")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private var notificationTextCache = ""
    private fun updateNotificationText(text: String) {
        if (text == notificationTextCache) return
        notificationTextCache = text
        updateNotification(text)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildForegroundNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        monitorJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "AppMonitorService destroyed")
    }
}
