package com.focusgate.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.focusgate.app.FocusGateApp
import com.focusgate.app.MainActivity
import com.focusgate.app.R
import com.focusgate.app.ai.GeminiService
import com.focusgate.app.state.FocusStateManager
import com.focusgate.app.state.NudgeLevel
import com.focusgate.app.ui.nudge.NudgeOverlayManager
import com.focusgate.app.ui.workmode.WorkModeActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * FocusAccessibilityService
 * ──────────────────────────
 * The gatekeeper engine of FocusGate.
 *
 * Responsibilities:
 *  1. Track foreground app changes (typeWindowStateChanged events)
 *  2. Compare active app against user's stated intent via Gemini
 *  3. Escalate the Nudge System (Warning → Grayscale → Block)
 *  4. Apply system-level grayscale filter via WindowManager overlay
 *  5. Enforce Work Mode by intercepting home/back in WorkModeActivity
 *  6. Dismiss grayscale when intent is re-aligned
 */
class FocusAccessibilityService : AccessibilityService() {

    // ─── Dependencies ────────────────────────────────────────────────────────

    private lateinit var stateManager: FocusStateManager
    private lateinit var geminiService: GeminiService
    private lateinit var nudgeOverlayManager: NudgeOverlayManager
    private lateinit var windowManager: WindowManager

    private val serviceScope   = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler    = Handler(Looper.getMainLooper())

    // Grayscale overlay view (drawn over entire screen)
    private var grayscaleOverlay: FrameLayout? = null
    private var isGrayscaleActive = false

    // Debounce: avoid rapid-fire AI calls on every window change
    private var lastCheckedPackage: String = ""
    private var intentCheckJob: Job? = null
    private val INTENT_CHECK_DEBOUNCE_MS = 3000L  // 3s debounce

    // Exception apps that bypass all checks
    private val exemptPackages = mutableSetOf(
        packageName,                         // FocusGate itself
        "com.android.systemui",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.settings"
    )

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()

        stateManager        = (application as FocusGateApp).focusStateManager
        geminiService       = GeminiService(applicationContext)
        nudgeOverlayManager = NudgeOverlayManager(applicationContext)
        windowManager       = getSystemService(WINDOW_SERVICE) as WindowManager

        // Configure event types to listen for
        serviceInfo = serviceInfo?.apply {
            eventTypes    = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType  = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags         = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        startForegroundCompat()
        loadExceptionApps()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == lastCheckedPackage) return
        if (packageName in exemptPackages) return

        lastCheckedPackage = packageName

        // Fire intent-mismatch check with debounce
        intentCheckJob?.cancel()
        intentCheckJob = serviceScope.launch {
            delay(INTENT_CHECK_DEBOUNCE_MS)
            handleForegroundAppChange(packageName)
        }
    }

    override fun onInterrupt() {
        serviceScope.cancel()
        removeGrayscaleOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeGrayscaleOverlay()
        nudgeOverlayManager.dismissAll()
    }

    // ─── Core Logic: Foreground App Change ───────────────────────────────────

    private suspend fun handleForegroundAppChange(activePackage: String) {
        // 1. Check if Work Mode is active
        if (stateManager.isWorkModeActiveNow()) {
            handleWorkModeViolation(activePackage)
            return
        }

        // 2. Check if Gate is enabled and there's a current intent
        val gateEnabled = stateManager.isGateEnabled.first()
        if (!gateEnabled) return

        val currentIntent = stateManager.getCurrentIntentNow() ?: return

        // 3. Perform AI intent-matching check
        performIntentMatchCheck(activePackage, currentIntent)
    }

    // ─── Work Mode Enforcement ───────────────────────────────────────────────

    private suspend fun handleWorkModeViolation(activePackage: String) {
        val allowedApps = stateManager.getAllowedAppsNow()

        // Package is in allowed list – no action needed
        if (activePackage in allowedApps) {
            removeGrayscaleOverlay()
            return
        }

        // Package is NOT allowed – return user to Work Mode
        // Use performGlobalAction to go home, then launch WorkModeActivity
        performGlobalAction(GLOBAL_ACTION_HOME)
        delay(300)

        val intent = Intent(this, WorkModeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                     Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                     Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    // ─── AI Intent Match Check ───────────────────────────────────────────────

    private suspend fun performIntentMatchCheck(activePackage: String, statedIntent: String) {
        val appLabel = getAppLabel(activePackage)

        val isMatch = try {
            geminiService.checkIntentMatch(
                statedIntent   = statedIntent,
                activeAppName  = appLabel,
                activePackage  = activePackage
            )
        } catch (e: Exception) {
            // On network failure, give benefit of the doubt (no false positives)
            true
        }

        if (!isMatch) {
            handleIntentMismatch(appLabel, statedIntent)
        } else {
            // Intent matches → reset nudge level, remove grayscale
            stateManager.resetNudge()
            if (isGrayscaleActive) removeGrayscaleOverlay()
        }
    }

    // ─── Nudge Escalation ────────────────────────────────────────────────────

    private suspend fun handleIntentMismatch(appName: String, originalIntent: String) {
        stateManager.escalateNudge()
        val nudgeLevel = stateManager.nudgeLevel.first()

        when (nudgeLevel) {
            NudgeLevel.NONE    -> { /* shouldn't happen */ }

            NudgeLevel.WARNING -> {
                // Level 1: Show gentle visual reminder overlay
                mainHandler.post {
                    nudgeOverlayManager.showWarningNudge(
                        currentApp    = appName,
                        originalIntent = originalIntent
                    )
                }
            }

            NudgeLevel.GRAYSCALE -> {
                // Level 2: Apply grayscale to entire screen
                nudgeOverlayManager.dismissWarning()
                mainHandler.post {
                    applyGrayscaleOverlay()
                    nudgeOverlayManager.showGrayscaleNudge(originalIntent)
                }
            }

            NudgeLevel.BLOCK -> {
                // Level 3: Block app, force back to home
                removeGrayscaleOverlay()
                nudgeOverlayManager.dismissAll()
                performGlobalAction(GLOBAL_ACTION_HOME)
                delay(200)
                nudgeOverlayManager.showBlockedNudge(appName, originalIntent)
            }
        }
    }

    // ─── Grayscale Overlay ───────────────────────────────────────────────────
    //
    // Implementation: Draws a transparent FrameLayout with a Paint that has a
    // grayscale ColorMatrixColorFilter over the ENTIRE screen using TYPE_ACCESSIBILITY_OVERLAY.
    // This is the correct, accessibility-sanctioned approach for this effect.

    private fun applyGrayscaleOverlay() {
        if (isGrayscaleActive) return

        val overlay = FrameLayout(this).apply {
            // Grayscale paint
            val grayMatrix = ColorMatrix().apply { setSaturation(0f) }
            val grayFilter = ColorMatrixColorFilter(grayMatrix)
            val paint = Paint().apply { colorFilter = grayFilter }
            // Apply to the layer
            setLayerType(FrameLayout.LAYER_TYPE_HARDWARE, paint)
            alpha = 0f
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(overlay, params)
            grayscaleOverlay = overlay
            isGrayscaleActive = true

            // Fade in
            overlay.animate()
                .alpha(1f)
                .setDuration(600)
                .start()
        } catch (e: Exception) {
            // Window manager may reject if accessibility overlay not permitted
            android.util.Log.e("FocusGate", "Failed to add grayscale overlay", e)
        }
    }

    private fun removeGrayscaleOverlay() {
        val overlay = grayscaleOverlay ?: return
        overlay.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                try {
                    windowManager.removeView(overlay)
                } catch (_: Exception) { }
                grayscaleOverlay = null
                isGrayscaleActive = false
            }
            .start()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun loadExceptionApps() {
        serviceScope.launch {
            stateManager.getExceptionApps().collect { apps ->
                exemptPackages.addAll(apps)
            }
        }
    }

    private fun startForegroundCompat() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FocusGateApp.CHANNEL_ID_FOCUS_MONITOR)
            .setContentTitle("FocusGate Active")
            .setContentText("Monitoring your focus session")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        startForeground(1001, notification)
    }
}
