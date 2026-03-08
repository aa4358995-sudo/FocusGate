package com.focusgate.app.ui.onboarding

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.focusgate.app.FocusGateApp

/**
 * OnboardingActivity
 * ───────────────────
 * Stub: The onboarding flow is rendered inside MainActivity
 * via the OnboardingGate composable when onboarding is not complete.
 * This activity class is declared in the manifest as a placeholder.
 */
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish() // Redirect to MainActivity which handles onboarding
    }
}
