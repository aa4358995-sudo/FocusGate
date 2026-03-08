package com.focusgate.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.focusgate.FocusGateApplication
import com.focusgate.data.DeviationEvent
import com.focusgate.data.FocusRepository
import com.focusgate.data.PreferencesManager
import com.focusgate.ui.IntentGateActivity
import com.focusgate.utils.NudgeManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * FocusAccessibilityService — The sentinel of FocusGate.
 *
 * Responsibilities:
 * 1. Monitor foreground app changes
 * 2. Enforce Work Mode app restrictions (block non-whitelisted apps)
 * 3. Escalate the Nudge System when intent mismatch is detected
 * 4. Apply grayscale filter at Nudge Level 2
 * 5. Handle HOME button intercept during Work Mode
 */
class FocusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusAccessibility"
        const val ACTION_STOP_WORK_MODE = "com.focusgate.STOP_WORK_MODE"
        const val ACTION_RELOAD_PREFS = "com.focusgate.RELOAD_PREFS"

        // Debounce interval — avoid rapid-fire analysis
        private const val ANALYSIS_DEBOUNCE_MS = 2000L

        @Volatile var instance: FocusAccessibilityService? = null
            private set

        fun isRunning() = instance != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: FocusRepository
    private lateinit var nudgeManager: NudgeManager
    private val handler = Handler(Looper.getMainLooper())

    // State cache (refreshed from DataStore)
    private var isWorkModeActive = false
    private var whitelistedApps = emptySet<String>()
    private var currentIntent = ""
    private var currentSessionId = ""
    private var exemptApps = PreferencesManager.DEFAULT_EXEMPT_APPS
    private var nudgeLevel = 0
    private var workModeEndTime = 0L

    // Debounce tracking
    private var lastAnalyzedPackage = ""
    private var lastAnalysisTime = 0L

    // Grayscale overlay view
    private var grayscaleOverlayActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = FocusGateApplication.instance.preferencesManager
        repository = FocusRepository(this)
        nudgeManager = NudgeManager(this)
        observePreferences()
        Log.i(TAG, "FocusAccessibilityService created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.i(TAG, "Accessibility service connected and configured")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // Skip our own app and system UI
        if (packageName == "com.focusgate" ||
            packageName == "com.android.systemui" ||
            packageName.startsWith("com.android.launcher")) return

        // Debounce — same package analyzed recently
        if (packageName == lastAnalyzedPackage && now - lastAnalysisTime < ANALYSIS_DEBOUNCE_MS) return
        lastAnalyzedPackage = packageName
        lastAnalysisTime = now

        handleForegroundAppChange(packageName)
    }

    private fun handleForegroundAppChange(packageName: String) {
        // Check Work Mode restrictions first
        if (isWorkModeActive) {
            handleWorkModeRestriction(packageName)
            return
        }

        // Non-work-mode: check intent alignment
        if (currentIntent.isNotBlank() && !exemptApps.contains(packageName)) {
            scheduleIntentAlignmentCheck(packageName)
        }
    }

    // ─── Work Mode Enforcement ────────────────────────────────────────────────

    private fun handleWorkModeRestriction(packageName: String) {
        // Check if Work Mode timer has expired
        if (workModeEndTime > 0 && System.currentTimeMillis() > workModeEndTime) {
            serviceScope.launch {
                prefs.endWorkMode()
                isWorkModeActive = false
                notifyWorkModeExpired()
            }
            return
        }

        val isWhitelisted = whitelistedApps.contains(packageName) ||
                exemptApps.contains(packageName)

        if (!isWhitelisted) {
            Log.d(TAG, "BLOCKED: $packageName is not whitelisted in Work Mode")
            // Force return to Work Mode activity
            handler.post {
                val intent = Intent(this, com.focusgate.ui.WorkModeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    putExtra("BLOCKED_APP", packageName)
                }
                startActivity(intent)
                vibratePattern(longArrayOf(0, 50, 50, 50))
            }
        }
    }

    // ─── Intent Alignment Check ──────────────────────────────────────────────

    private fun scheduleIntentAlignmentCheck(packageName: String) {
        serviceScope.launch {
            try {
                val appLabel = getAppLabel(packageName)
                checkIntentAlignment(packageName, appLabel)
            } catch (e: Exception) {
                Log.e(TAG, "Error in intent check: ${e.message}")
            }
        }
    }

    private suspend fun checkIntentAlignment(packageName: String, appLabel: String) {
        if (currentIntent.isBlank()) return

        // Get AI service
        val geminiKey = prefs.getApiKeyOnce("gemini")
        if (geminiKey.isBlank()) return

        val aiService = com.focusgate.ai.GeminiAIService(geminiKey)
        val result = aiService.analyzeIntentAlignment(
            statedIntent = currentIntent,
            foregroundApp = packageName,
            appLabel = appLabel
        )

        if (!result.isAligned && result.confidence > 0.7f) {
            Log.w(TAG, "Intent mismatch detected: $packageName vs intent '$currentIntent' (confidence: ${result.confidence})")
            handleIntentMismatch(packageName, appLabel, result)
        } else {
            // Aligned — gradually reduce nudge level
            if (nudgeLevel > 0) {
                prefs.resetNudge()
                nudgeLevel = 0
                removeGrayscaleFilter()
            }
        }
    }

    private suspend fun handleIntentMismatch(
        packageName: String,
        appLabel: String,
        analysis: com.focusgate.ai.IntentAnalysisResult
    ) {
        // Record the deviation
        val sessionId = currentSessionId
        if (sessionId.isNotBlank()) {
            repository.recordDeviation(
                DeviationEvent(
                    sessionId = sessionId,
                    statedIntent = currentIntent,
                    actualApp = packageName,
                    actualAppName = appLabel,
                    timestamp = System.currentTimeMillis(),
                    nudgeLevel = nudgeLevel,
                    aiAnalysis = analysis.reasoning
                )
            )
        }

        // Escalate nudge
        prefs.escalateNudge()
        nudgeLevel = minOf(nudgeLevel + 1, 3)

        handler.post {
            when (nudgeLevel) {
                1 -> nudgeManager.showLevel1Warning(currentIntent, appLabel, analysis.nudgeSuggestion)
                2 -> {
                    nudgeManager.showLevel2Warning(currentIntent, appLabel)
                    applyGrayscaleFilter()
                }
                3 -> {
                    nudgeManager.dismissAll()
                    removeGrayscaleFilter()
                    forceReturnHome()
                }
            }
        }
    }

    // ─── Grayscale Filter (Nudge Level 2) ────────────────────────────────────

    private fun applyGrayscaleFilter() {
        if (grayscaleOverlayActive) return
        grayscaleOverlayActive = true
        // Note: True screen grayscale requires ColorDisplayManager (system app level)
        // We use a semi-transparent gray overlay as the accessible implementation
        Log.i(TAG, "Grayscale filter applied (Nudge Level 2)")
        // Signal to NudgeManager to show gray overlay
        nudgeManager.applyGrayOverlay()
    }

    private fun removeGrayscaleFilter() {
        if (!grayscaleOverlayActive) return
        grayscaleOverlayActive = false
        nudgeManager.removeGrayOverlay()
        Log.i(TAG, "Grayscale filter removed")
    }

    // ─── Home Force Return (Nudge Level 3) ───────────────────────────────────

    private fun forceReturnHome() {
        Log.w(TAG, "NUDGE LEVEL 3: Forcing return to home screen")
        performGlobalAction(GLOBAL_ACTION_HOME)
        vibratePattern(longArrayOf(0, 100, 50, 100, 50, 100))
    }

    // ─── Work Mode Expiry ─────────────────────────────────────────────────────

    private fun notifyWorkModeExpired() {
        val intent = Intent(this, com.focusgate.ui.DigestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ─── Preferences Observer ────────────────────────────────────────────────

    private fun observePreferences() {
        serviceScope.launch {
            prefs.isWorkModeActive.collect { active ->
                isWorkModeActive = active
                if (!active) {
                    removeGrayscaleFilter()
                    nudgeLevel = 0
                }
            }
        }
        serviceScope.launch {
            prefs.workModeWhitelistedApps.collect { apps ->
                whitelistedApps = apps
            }
        }
        serviceScope.launch {
            prefs.workModeEndTime.collect { time ->
                workModeEndTime = time
            }
        }
        serviceScope.launch {
            prefs.currentIntent.collect { intent ->
                currentIntent = intent
                if (intent.isNotBlank()) nudgeLevel = 0
            }
        }
        serviceScope.launch {
            prefs.exemptApps.collect { apps ->
                exemptApps = apps
            }
        }
        serviceScope.launch {
            prefs.nudgeLevel.collect { level ->
                nudgeLevel = level
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(VibratorManager::class.java)
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Vibrator::class.java)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibration failed: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        serviceScope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
        removeGrayscaleFilter()
        nudgeManager.dismissAll()
        Log.i(TAG, "FocusAccessibilityService destroyed")
    }
}
