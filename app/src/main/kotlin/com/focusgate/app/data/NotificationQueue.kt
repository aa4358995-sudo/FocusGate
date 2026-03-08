package com.focusgate.app.data

import java.util.concurrent.CopyOnWriteArrayList

/**
 * InterceptedNotification
 * ────────────────────────
 * Represents a single notification captured during Work Mode.
 * This data is sent to Gemini for the Post-Focus Digest.
 */
data class InterceptedNotification(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val category: String   // Notification.CATEGORY_* values
)

/**
 * NotificationQueue
 * ──────────────────
 * Thread-safe in-memory queue for intercepted notifications.
 * CopyOnWriteArrayList ensures safe concurrent reads/writes
 * from the NotificationListenerService callback thread.
 *
 * Note: This is intentionally in-memory only during the session.
 * Notifications are sensitive user data and should not be
 * persisted to disk longer than necessary.
 */
object NotificationQueue {
    private val queue = CopyOnWriteArrayList<InterceptedNotification>()

    fun add(notification: InterceptedNotification) {
        // Cap at 100 notifications to prevent memory issues in long sessions
        if (queue.size >= 100) queue.removeAt(0)
        queue.add(notification)
    }

    fun drainAll(): List<InterceptedNotification> {
        val copy = queue.toList()
        queue.clear()
        return copy
    }

    fun peek(): List<InterceptedNotification> = queue.toList()

    fun size() = queue.size

    fun clear() = queue.clear()
}
