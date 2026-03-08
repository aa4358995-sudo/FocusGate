package com.focusgate.app.services

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.focusgate.app.FocusGateApp
import com.focusgate.app.MainActivity
import com.focusgate.app.receiver.ScreenStateReceiver

/**
 * FocusMonitorService
 * ────────────────────
 * A persistent foreground service that ensures FocusGate
 * remains active even when the app is killed by the OS.
 *
 * Responsibilities:
 *  1. Register screen state receiver dynamically (for reliable unlock detection)
 *  2. Keep the process alive to receive AccessibilityService events
 *  3. Auto-restart on kill via START_STICKY
 *
 * Note: The AccessibilityService is the primary monitoring mechanism.
 * This service acts as a keepalive and event bridge.
 */
class FocusMonitorService : Service() {

    private var screenReceiver: ScreenStateReceiver? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: system will restart service if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        screenReceiver?.let { unregisterReceiver(it) }
    }

    private fun registerScreenReceiver() {
        screenReceiver = ScreenStateReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    private fun startForegroundCompat() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FocusGateApp.CHANNEL_ID_FOCUS_MONITOR)
            .setContentTitle("FocusGate")
            .setContentText("Your focus companion is active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        startForeground(1002, notification)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FocusMonitorService::class.java)
            context.startForegroundService(intent)
        }
    }
}
