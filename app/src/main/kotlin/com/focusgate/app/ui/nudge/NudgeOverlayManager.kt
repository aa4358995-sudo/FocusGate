package com.focusgate.app.ui.nudge

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.focusgate.app.ui.theme.FocusColors
import com.focusgate.app.ui.theme.FocusGateTheme

/**
 * NudgeOverlayManager
 * ────────────────────
 * Manages the three levels of nudge overlays using WindowManager.
 * All overlays are implemented as ComposeViews added directly to the
 * window manager, allowing them to appear above all apps.
 *
 * Level 1 – Warning:   Slides in from top, auto-dismisses in 5 seconds
 * Level 2 – Grayscale: Companion text banner explaining the gray filter
 * Level 3 – Blocked:   Bottom sheet explaining the app was blocked
 */
class NudgeOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var warningView: View? = null
    private var blockedView: View? = null
    private var grayscaleNudgeView: View? = null

    // ─── Level 1: Warning Nudge ───────────────────────────────────────────

    fun showWarningNudge(currentApp: String, originalIntent: String) {
        dismissWarning()

        val view = createComposeOverlay { lifecycle ->
            WarningNudgeBanner(
                currentApp     = currentApp,
                originalIntent = originalIntent,
                onDismiss      = { dismissWarning() }
            )
        }

        addOverlayView(
            view    = view,
            gravity = Gravity.TOP,
            height  = WindowManager.LayoutParams.WRAP_CONTENT
        )
        warningView = view

        // Auto-dismiss after 5 seconds
        mainHandler.postDelayed({ dismissWarning() }, 5000)
    }

    // ─── Level 2: Grayscale Companion Nudge ──────────────────────────────

    fun showGrayscaleNudge(originalIntent: String) {
        dismissGrayscaleNudge()

        val view = createComposeOverlay { _ ->
            GrayscaleNudgeBanner(
                originalIntent = originalIntent,
                onDismiss      = { dismissGrayscaleNudge() }
            )
        }

        addOverlayView(
            view    = view,
            gravity = Gravity.BOTTOM,
            height  = WindowManager.LayoutParams.WRAP_CONTENT
        )
        grayscaleNudgeView = view
    }

    // ─── Level 3: Block Nudge ─────────────────────────────────────────────

    fun showBlockedNudge(appName: String, originalIntent: String) {
        dismissBlocked()

        val view = createComposeOverlay { _ ->
            BlockedNudgeBanner(
                appName        = appName,
                originalIntent = originalIntent,
                onDismiss      = { dismissBlocked() }
            )
        }

        addOverlayView(
            view    = view,
            gravity = Gravity.BOTTOM,
            height  = WindowManager.LayoutParams.WRAP_CONTENT
        )
        blockedView = view

        // Auto-dismiss after 8 seconds
        mainHandler.postDelayed({ dismissBlocked() }, 8000)
    }

    // ─── Dismiss methods ──────────────────────────────────────────────────

    fun dismissWarning() {
        warningView?.let { removeView(it) }
        warningView = null
    }

    fun dismissGrayscaleNudge() {
        grayscaleNudgeView?.let { removeView(it) }
        grayscaleNudgeView = null
    }

    fun dismissBlocked() {
        blockedView?.let { removeView(it) }
        blockedView = null
    }

    fun dismissAll() {
        dismissWarning()
        dismissGrayscaleNudge()
        dismissBlocked()
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    private fun createComposeOverlay(
        content: @Composable (lifecycle: LifecycleOwner) -> Unit
    ): View {
        val composeView = ComposeView(context).apply {
            // ComposeView needs a lifecycle owner to function
            val lifecycleOwner = SimpleLifecycleOwner()
            lifecycleOwner.start()
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
            )
            setContent {
                FocusGateTheme {
                    content(lifecycleOwner)
                }
            }
        }
        return composeView
    }

    private fun addOverlayView(view: View, gravity: Int, height: Int) {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            android.util.Log.e("NudgeOverlay", "Failed to add overlay view", e)
        }
    }

    private fun removeView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) { }
    }
}

// ─── Simple LifecycleOwner for ComposeView ────────────────────────────────────

class SimpleLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun start() {
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

// ─── Nudge Banner Composables ─────────────────────────────────────────────────

@Composable
fun WarningNudgeBanner(
    currentApp: String,
    originalIntent: String,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter   = slideInVertically { -it } + fadeIn(),
        exit    = slideOutVertically { -it } + fadeOut()
    ) {
        Card(
            modifier  = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape     = RoundedCornerShape(16.dp),
            colors    = CardDefaults.cardColors(
                containerColor = FocusColors.Cream
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier  = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Amber dot indicator
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(FocusColors.NudgeWarning, shape = RoundedCornerShape(5.dp))
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = "Gentle Reminder 🌿",
                        style = MaterialTheme.typography.titleMedium,
                        color = FocusColors.EarthDeep
                    )
                    Text(
                        text  = "You said: \"$originalIntent\"\nYou're in: $currentApp",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.EarthMid
                    )
                }

                IconButton(onClick = onDismiss) {
                    Text("×", style = MaterialTheme.typography.titleLarge, color = FocusColors.EarthLight)
                }
            }
        }
    }
}

@Composable
fun GrayscaleNudgeBanner(originalIntent: String, onDismiss: () -> Unit) {
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = FocusColors.Cream),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier  = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "◉  Grayscale Mode Active",
                style = MaterialTheme.typography.titleMedium,
                color = FocusColors.NudgeGrayscale
            )
            Text(
                "Color has been removed to reduce stimulation.\nYou intended: \"$originalIntent\"",
                style     = MaterialTheme.typography.bodySmall,
                color     = FocusColors.EarthMid,
                textAlign = TextAlign.Center
            )
            OutlinedButton(
                onClick = onDismiss,
                shape   = RoundedCornerShape(12.dp),
                border  = BorderStroke(1.dp, FocusColors.SageDeep)
            ) {
                Text("Return to intent", color = FocusColors.SageDeep)
            }
        }
    }
}

@Composable
fun BlockedNudgeBanner(
    appName: String,
    originalIntent: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = FocusColors.Cream
        ),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(
            modifier  = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("App Blocked", style = MaterialTheme.typography.titleLarge, color = FocusColors.NudgeBlock)
            Text(
                "$appName was blocked – it doesn't match your stated intent.",
                style     = MaterialTheme.typography.bodyMedium,
                color     = FocusColors.EarthMid,
                textAlign = TextAlign.Center
            )
            Text(
                "\"$originalIntent\"",
                style     = MaterialTheme.typography.bodySmall,
                color     = FocusColors.SageDeep,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onDismiss,
                colors  = ButtonDefaults.buttonColors(containerColor = FocusColors.SageDeep)
            ) {
                Text("I understand – stay focused", color = FocusColors.Ivory)
            }
        }
    }
}
