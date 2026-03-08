package com.focusgate.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.focusgate.app.FocusGateApp
import com.focusgate.app.ui.intentgate.IntentGateActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * ScreenStateReceiver
 * ────────────────────
 * Listens for:
 *  • USER_PRESENT  → Screen fully unlocked, show Intent Gate
 *  • SCREEN_ON     → Screen woke up (may still show keyguard)
 *  • SCREEN_OFF    → Screen turned off, clear intent session
 *  • BOOT_COMPLETED → Restart monitoring service after reboot
 *
 * Note on USER_PRESENT vs SCREEN_ON:
 *  SCREEN_ON fires when screen turns on but before PIN/fingerprint.
 *  USER_PRESENT fires only after the user has passed the keyguard.
 *  We use USER_PRESENT so the gate fires AFTER the user unlocks.
 */
class ScreenStateReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT  -> handleUnlock(context)
            Intent.ACTION_SCREEN_OFF    -> handleScreenOff(context)
            Intent.ACTION_BOOT_COMPLETED -> handleBoot(context)
        }
    }

    private fun handleUnlock(context: Context) {
        val stateManager = (context.applicationContext as FocusGateApp).focusStateManager

        scope.launch {
            // Don't show gate if Work Mode is active (handled by AccessibilityService)
            if (stateManager.isWorkModeActiveNow()) return@launch

            // Don't show gate if gate is disabled
            val gateEnabled = stateManager.isGateEnabled.first()
            if (!gateEnabled) return@launch

            // Clear any stale intent from a previous session
            // (Intents are cleared on screen off, but this is a safety check)
            val intent = IntentGateActivity.createIntent(context)
            context.startActivity(intent)
        }
    }

    private fun handleScreenOff(context: Context) {
        // When screen goes off, clear the current intent
        // This forces a fresh declaration on the next unlock
        val stateManager = (context.applicationContext as FocusGateApp).focusStateManager
        stateManager.clearCurrentIntent()
    }

    private fun handleBoot(context: Context) {
        // After reboot, check if Work Mode was active and end it gracefully
        // (Timer can't run while device is off)
        val stateManager = (context.applicationContext as FocusGateApp).focusStateManager
        scope.launch {
            if (stateManager.isWorkModeActiveNow()) {
                // Work Mode is "active" but device was rebooted – end it
                stateManager.endWorkMode()
            }
        }
    }
}
