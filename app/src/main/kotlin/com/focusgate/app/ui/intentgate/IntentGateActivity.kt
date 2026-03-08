package com.focusgate.app.ui.intentgate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import com.focusgate.app.FocusGateApp
import com.focusgate.app.ai.GeminiService
import com.focusgate.app.ai.IntentCategory
import com.focusgate.app.ui.theme.FocusColors
import com.focusgate.app.ui.theme.FocusGateTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.sin

/**
 * IntentGateActivity
 * ───────────────────
 * "The Oasis" – The full-screen pattern interrupt overlay.
 *
 * Shown whenever the user unlocks their phone.
 * Forces intentional declaration before granting access.
 *
 * Design: Minimalist zen aesthetic. Calming gradient background
 * with a breathing animation, serif prompt text, and a single
 * text input. No distractions, no clutter.
 *
 * Cannot be dismissed by Back/Recents. Must enter intent to proceed.
 * Exception: Physical phone call bypasses this entirely.
 */
class IntentGateActivity : ComponentActivity() {

    private lateinit var stateManager: com.focusgate.app.state.FocusStateManager
    private lateinit var geminiService: GeminiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        stateManager  = (application as FocusGateApp).focusStateManager
        geminiService = GeminiService(applicationContext)

        // Show above lock screen, keep screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED     or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD     or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON       or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Consume back button – cannot dismiss gate without intent
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Gentle vibration feedback – you must state your intent
                vibratePattern()
            }
        })

        // If this is an emergency bypass intent, finish immediately
        if (intent.getBooleanExtra(EXTRA_EMERGENCY_BYPASS, false)) {
            finish()
            return
        }

        setContent {
            FocusGateTheme {
                IntentGateScreen(
                    onIntentSubmitted = { intentText ->
                        handleIntentSubmission(intentText)
                    }
                )
            }
        }
    }

    private fun handleIntentSubmission(intentText: String) {
        val sessionId = UUID.randomUUID().toString()
        stateManager.setCurrentIntent(intentText, sessionId)

        lifecycleScope.launch {
            // Classify intent category in background (non-blocking)
            try {
                geminiService.parseIntentCategory(intentText)
            } catch (_: Exception) { }
        }

        // Small delay for the "committed" animation to play, then close
        lifecycleScope.launch {
            delay(600)
            finish()
            overridePendingTransition(0, android.R.anim.fade_out)
        }
    }

    private fun vibratePattern() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60), -1))
    }

    companion object {
        const val EXTRA_EMERGENCY_BYPASS = "emergency_bypass"

        fun createIntent(context: Context) = Intent(context, IntentGateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                     Intent.FLAG_ACTIVITY_SINGLE_TOP or
                     Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}

// ─── Composable UI ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntentGateScreen(onIntentSubmitted: (String) -> Unit) {
    var intentText by remember { mutableStateOf(TextFieldValue("")) }
    var isSubmitted by remember { mutableStateOf(false) }
    var errorShake  by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }

    // ── Breathing animation ────────────────────────────────────────────────
    val breathingAnim = rememberInfiniteTransition(label = "breathing")
    val breathScale by breathingAnim.animateFloat(
        initialValue = 0.95f,
        targetValue  = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathScale"
    )
    val breathAlpha by breathingAnim.animateFloat(
        initialValue = 0.3f,
        targetValue  = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathAlpha"
    )

    // ── Entrance animation ─────────────────────────────────────────────────
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        visible = true
        delay(400)
        focusRequester.requestFocus()
    }

    // ── Shake animation for validation feedback ────────────────────────────
    val shakeOffset by animateFloatAsState(
        targetValue = if (errorShake) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "shake"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.Ivory),
        contentAlignment = Alignment.Center
    ) {

        // ── Ambient gradient background ────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Soft sage gradient wash
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FocusColors.SagePale.copy(alpha = 0.4f),
                        FocusColors.Ivory
                    ),
                    center = Offset(w * 0.3f, h * 0.2f),
                    radius = w * 0.8f
                )
            )
            // Bottom earth warmth
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        FocusColors.Linen.copy(alpha = 0.5f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.7f, h * 0.9f),
                    radius = w * 0.6f
                )
            )
        }

        // ── Breathing orb ─────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .size(280.dp)
                .scale(breathScale)
                .alpha(breathAlpha)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            FocusColors.SageLight.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    ),
                    shape = CircleShape
                )
        )

        // ── Main content ───────────────────────────────────────────────────
        AnimatedVisibility(
            visible   = visible,
            enter     = fadeIn(tween(700)) + slideInVertically(
                tween(700, easing = EaseOutCubic),
                initialOffsetY = { it / 3 }
            )
        ) {
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .offset(x = (shakeOffset * sin(shakeOffset * 12f) * 8f).dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {

                // ── Gate indicator ─────────────────────────────────────────
                Text(
                    text  = "⌁",
                    style = MaterialTheme.typography.displayMedium,
                    color = FocusColors.SageDeep.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ── Headline prompt ────────────────────────────────────────
                Text(
                    text      = if (isSubmitted) "Carry on, with intention."
                                else "Why are you\nopening your phone?",
                    style     = MaterialTheme.typography.headlineLarge,
                    color     = FocusColors.EarthDeep,
                    textAlign = TextAlign.Center
                )

                // ── Subheading ─────────────────────────────────────────────
                AnimatedVisibility(visible = !isSubmitted) {
                    Text(
                        text      = "Take a breath. State your purpose.",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = FocusColors.EarthLight,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Intent input ───────────────────────────────────────────
                AnimatedVisibility(visible = !isSubmitted) {
                    OutlinedTextField(
                        value         = intentText,
                        onValueChange = { intentText = it },
                        modifier      = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder   = {
                            Text(
                                text  = "e.g. Check work email, study Python, call Mom...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FocusColors.EarthLight
                            )
                        },
                        textStyle     = MaterialTheme.typography.bodyLarge.copy(
                            color = FocusColors.EarthDeep
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (intentText.text.isBlank()) {
                                    errorShake = true
                                } else {
                                    isSubmitted = true
                                    onIntentSubmitted(intentText.text.trim())
                                }
                            }
                        ),
                        shape         = RoundedCornerShape(16.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = FocusColors.SageDeep,
                            unfocusedBorderColor = FocusColors.Linen,
                            focusedContainerColor   = FocusColors.Cream,
                            unfocusedContainerColor = FocusColors.Cream,
                            cursorColor          = FocusColors.SageDeep
                        ),
                        singleLine    = false,
                        maxLines      = 3,
                        minLines      = 2
                    )
                }

                // ── Submit button ──────────────────────────────────────────
                AnimatedVisibility(visible = !isSubmitted) {
                    Button(
                        onClick = {
                            if (intentText.text.isBlank()) {
                                errorShake = true
                            } else {
                                isSubmitted = true
                                onIntentSubmitted(intentText.text.trim())
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape    = RoundedCornerShape(16.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = FocusColors.SageDeep,
                            contentColor   = FocusColors.Ivory
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp
                        )
                    ) {
                        Text(
                            text  = "Enter with purpose",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                // ── Suggestion chips ───────────────────────────────────────
                AnimatedVisibility(
                    visible = !isSubmitted && intentText.text.isBlank()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text  = "Quick intents",
                            style = MaterialTheme.typography.labelLarge,
                            color = FocusColors.EarthLight
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            listOf(
                                "Check email",
                                "Quick call",
                                "Navigation",
                                "Study session",
                                "Bank transfer"
                            ).forEach { suggestion ->
                                SuggestionChip(
                                    onClick = {
                                        intentText = TextFieldValue(suggestion)
                                    },
                                    label   = {
                                        Text(
                                            suggestion,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    shape   = RoundedCornerShape(20.dp),
                                    colors  = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = FocusColors.SagePale,
                                        labelColor     = FocusColors.SageDeep
                                    ),
                                    border  = SuggestionChipDefaults.suggestionChipBorder(
                                        enabled        = true,
                                        borderColor    = FocusColors.SageLight.copy(alpha = 0.5f),
                                        borderWidth    = 1.dp
                                    )
                                )
                            }
                        }
                    }
                }

                // ── Submitted state ────────────────────────────────────────
                AnimatedVisibility(
                    visible = isSubmitted,
                    enter   = fadeIn() + scaleIn()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text  = "✓",
                            style = MaterialTheme.typography.displayMedium,
                            color = FocusColors.SageDeep
                        )
                        Text(
                            text  = "\"${intentText.text.trim()}\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = FocusColors.EarthMid,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // ── Bottom hint ────────────────────────────────────────────
                Spacer(modifier = Modifier.height(8.dp))
                if (!isSubmitted) {
                    Text(
                        text  = "FocusGate will gently guide you if you drift.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.EarthLight.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // Reset shake after animation
    LaunchedEffect(errorShake) {
        if (errorShake) {
            delay(500)
            errorShake = false
        }
    }
}
