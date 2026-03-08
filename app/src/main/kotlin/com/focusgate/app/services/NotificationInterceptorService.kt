package com.focusgate.app.services

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.focusgate.app.FocusGateApp
import com.focusgate.app.ai.GeminiService
import com.focusgate.app.data.InterceptedNotification
import com.focusgate.app.data.NotificationQueue
import kotlinx.coroutines.*

/**
 * NotificationInterceptorService
 * ────────────────────────────────
 * Intercepts all system notifications while Work Mode is active.
 *
 * Behavior:
 *  • DURING Work Mode: Silences all non-critical notifications.
 *    Queues them in NotificationQueue (in-memory + DataStore backup).
 *    EXCEPTION: Phone call notifications are ALWAYS allowed through.
 *
 *  • ON Work Mode END: Sends queued notifications to Gemini AI for
 *    summarization. Delivers a "Post-Focus Digest" notification.
 *
 *  • OUTSIDE Work Mode: Normal passthrough (no interception).
 */
class NotificationInterceptorService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var stateManager: com.focusgate.app.state.FocusStateManager
    private lateinit var geminiService: GeminiService

    // ─── Priority package patterns that bypass interception ─────────────────
    private val criticalPackagePatterns = listOf(
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.android.server.telecom"
    )

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        stateManager = (application as FocusGateApp).focusStateManager
        geminiService = GeminiService(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // ─── Notification Events ─────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val pkg = sbn.packageName

        // Always allow critical communications through
        if (isCriticalNotification(pkg, sbn)) return

        // Check if Work Mode is active; if not, pass through
        serviceScope.launch {
            if (!stateManager.isWorkModeActiveNow()) return@launch

            // ─── Intercept the notification ──────────────────────────────
            interceptNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Track removals (user dismissed, app cleared, etc.)
        // This helps with accurate queuing
    }

    // ─── Interception Logic ──────────────────────────────────────────────────

    private suspend fun interceptNotification(sbn: StatusBarNotification) {
        val extras: Bundle = sbn.notification.extras ?: Bundle()

        val title   = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text    = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val appName = getAppLabel(sbn.packageName)

        val notification = InterceptedNotification(
            id          = "${sbn.packageName}_${sbn.id}_${System.currentTimeMillis()}",
            packageName = sbn.packageName,
            appName     = appName,
            title       = title,
            text        = bigText ?: text,
            timestamp   = sbn.postTime,
            category    = sbn.notification.category ?: "general"
        )

        NotificationQueue.add(notification)

        // Silently cancel the notification so it doesn't appear in the shade
        try {
            cancelNotification(sbn.key)
        } catch (_: Exception) { }
    }

    // ─── AI Digest Generation ─────────────────────────────────────────────────

    /**
     * Called by WorkModeActivity when the timer ends.
     * Drains the queue and generates an AI summary.
     */
    suspend fun generateDigest(): DigestResult {
        val notifications = NotificationQueue.drainAll()

        if (notifications.isEmpty()) {
            return DigestResult(
                summary    = "No notifications were received during your focus session. Well done! 🎯",
                items      = emptyList(),
                urgentCount = 0,
                totalCount  = 0
            )
        }

        return try {
            val summary = geminiService.summarizeNotifications(notifications)
            DigestResult(
                summary     = summary,
                items       = notifications,
                urgentCount = notifications.count { it.category == Notification.CATEGORY_MESSAGE
                                                 || it.category == "email" },
                totalCount  = notifications.size
            )
        } catch (e: Exception) {
            // Fallback: manual grouping if AI fails
            val grouped = notifications.groupBy { it.appName }
            val fallbackSummary = buildString {
                appendLine("You received ${notifications.size} notification(s) while focusing:")
                grouped.forEach { (app, notifs) ->
                    appendLine("• $app: ${notifs.size} message(s)")
                }
            }
            DigestResult(
                summary     = fallbackSummary,
                items       = notifications,
                urgentCount = 0,
                totalCount  = notifications.size
            )
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun isCriticalNotification(pkg: String, sbn: StatusBarNotification): Boolean {
        if (criticalPackagePatterns.any { pkg.contains(it) }) return true
        val category = sbn.notification.category
        return category == Notification.CATEGORY_CALL ||
               category == Notification.CATEGORY_ALARM
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    companion object {
        @Volatile
        private var instance: NotificationInterceptorService? = null

        fun getInstance() = instance

        fun isRunning() = instance != null
    }

    init { instance = this }
}

// ─── Data Models ─────────────────────────────────────────────────────────────

data class DigestResult(
    val summary: String,
    val items: List<com.focusgate.app.data.InterceptedNotification>,
    val urgentCount: Int,
    val totalCount: Int
)
