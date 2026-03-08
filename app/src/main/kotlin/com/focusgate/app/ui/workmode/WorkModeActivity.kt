package com.focusgate.app.ui.workmode

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import com.focusgate.app.FocusGateApp
import com.focusgate.app.state.FocusStateManager
import com.focusgate.app.state.WorkModeState
import com.focusgate.app.ui.digest.PostFocusDigestActivity
import com.focusgate.app.ui.theme.FocusColors
import com.focusgate.app.ui.theme.FocusGateTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.random.Random

/**
 * WorkModeActivity
 * ─────────────────
 * The Focus Launcher – a minimal replacement home screen during sessions.
 *
 * When active:
 *  • Shows only whitelisted apps as large, calm tiles
 *  • Displays a prominent countdown timer
 *  • ALL other app launches are intercepted by AccessibilityService
 *  • Back button is consumed (cannot escape to normal launcher)
 *  • Home button returns to THIS activity (registered as HOME in manifest)
 *
 * Emergency Exit Protocol:
 *  • User must type a randomly generated 50-word paragraph perfectly
 *  • This friction is intentional – it must feel like real effort
 *
 * Session End:
 *  • Timer reaches zero → Work Mode deactivated
 *  • NotificationInterceptorService generates AI digest
 *  • Transitions to PostFocusDigestActivity
 */
class WorkModeActivity : ComponentActivity() {

    private lateinit var stateManager: FocusStateManager
    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateManager = (application as FocusGateApp).focusStateManager

        // Show above everything, keep screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Intercept back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Do nothing – back cannot exit Work Mode
            }
        })

        setContent {
            FocusGateTheme {
                WorkModeRoot(
                    stateManager    = stateManager,
                    onSessionEnd    = { navigateToDigest() },
                    onEmergencyExit = { performEmergencyExit() }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // If paused during Work Mode, the AccessibilityService will
        // route the user back here
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }

    private fun navigateToDigest() {
        lifecycleScope.launch {
            stateManager.endWorkMode()
            delay(300)
            startActivity(
                Intent(this@WorkModeActivity, PostFocusDigestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    private fun performEmergencyExit() {
        lifecycleScope.launch {
            stateManager.endWorkMode()
            finish()
        }
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, WorkModeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }
}

// ─── UI Root ──────────────────────────────────────────────────────────────────

@Composable
fun WorkModeRoot(
    stateManager: FocusStateManager,
    onSessionEnd: () -> Unit,
    onEmergencyExit: () -> Unit
) {
    val workModeState by stateManager.workModeState.collectAsState(
        initial = WorkModeState(false, 0, emptyList(), "")
    )

    // If Work Mode is not active, show setup screen
    if (!workModeState.isActive) {
        WorkModeSetupScreen(
            stateManager = stateManager,
            onSessionStarted = { /* state update triggers recompose */ }
        )
    } else {
        WorkModeLauncherScreen(
            workModeState = workModeState,
            onSessionEnd  = onSessionEnd,
            onEmergencyExit = onEmergencyExit
        )
    }
}

// ─── Setup Screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkModeSetupScreen(
    stateManager: FocusStateManager,
    onSessionStarted: () -> Unit
) {
    val context = LocalContext.current
    var sessionLabel by remember { mutableStateOf(TextFieldValue("")) }
    var selectedDuration by remember { mutableStateOf(25) } // Default: 25 min (Pomodoro)
    var selectedApps by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    // Load installed apps
    val installedApps = remember {
        val pm = context.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                AppInfo(
                    packageName = info.packageName,
                    label       = pm.getApplicationLabel(info).toString(),
                    icon        = pm.getApplicationIcon(info)
                )
            }
            .sortedBy { it.label }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.Ivory)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        // Header
        Text(
            text  = "Begin Focus Session",
            style = MaterialTheme.typography.headlineLarge,
            color = FocusColors.EarthDeep
        )
        Text(
            text  = "Configure your sanctuary. Only selected apps will be accessible.",
            style = MaterialTheme.typography.bodyMedium,
            color = FocusColors.EarthMid
        )

        HorizontalDivider(color = FocusColors.Linen)

        // Session label
        Text("Session Name", style = MaterialTheme.typography.titleMedium, color = FocusColors.EarthDeep)
        OutlinedTextField(
            value         = sessionLabel,
            onValueChange = { sessionLabel = it },
            modifier      = Modifier.fillMaxWidth(),
            placeholder   = { Text("e.g. Deep Work, Study Block, Writing...") },
            shape         = RoundedCornerShape(12.dp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = FocusColors.SageDeep,
                unfocusedBorderColor = FocusColors.Linen,
                focusedContainerColor   = FocusColors.Cream,
                unfocusedContainerColor = FocusColors.Cream
            ),
            singleLine    = true
        )

        // Duration selector
        Text("Duration", style = MaterialTheme.typography.titleMedium, color = FocusColors.EarthDeep)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 25, 45, 60, 90, 120).forEach { minutes ->
                FilterChip(
                    selected = selectedDuration == minutes,
                    onClick  = { selectedDuration = minutes },
                    label    = {
                        Text(
                            if (minutes < 60) "${minutes}m" else "${minutes / 60}h",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = FocusColors.SageDeep,
                        selectedLabelColor     = FocusColors.Ivory,
                        containerColor         = FocusColors.Cream,
                        labelColor             = FocusColors.EarthMid
                    ),
                    shape    = RoundedCornerShape(20.dp)
                )
            }
        }

        // Custom duration slider
        Text(
            "$selectedDuration minutes",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.EarthLight
        )
        Slider(
            value         = selectedDuration.toFloat(),
            onValueChange = { selectedDuration = it.toInt() },
            valueRange    = 5f..240f,
            steps         = 46,
            colors        = SliderDefaults.colors(
                thumbColor       = FocusColors.SageDeep,
                activeTrackColor = FocusColors.SageDeep,
                inactiveTrackColor = FocusColors.Linen
            )
        )

        // Whitelist apps
        Text("Allowed Apps", style = MaterialTheme.typography.titleMedium, color = FocusColors.EarthDeep)
        Text(
            "Only these apps will be accessible during the session. Essential apps (Phone, Camera) are always available.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.EarthLight
        )

        LazyVerticalGrid(
            columns         = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            modifier        = Modifier.height(300.dp)
        ) {
            items(installedApps.take(30)) { app ->
                AppSelectionCard(
                    app        = app,
                    isSelected = app.packageName in selectedApps,
                    onClick    = {
                        selectedApps = if (app.packageName in selectedApps) {
                            selectedApps - app.packageName
                        } else {
                            selectedApps + app.packageName
                        }
                    }
                )
            }
        }

        // Start button
        Button(
            onClick = {
                val label = sessionLabel.text.ifBlank { "Focus Session" }
                scope.launch {
                    stateManager.startWorkMode(
                        durationMinutes = selectedDuration,
                        allowedApps     = selectedApps.toList(),
                        label           = label
                    )
                    onSessionStarted()
                }
            },
            modifier  = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape     = RoundedCornerShape(16.dp),
            colors    = ButtonDefaults.buttonColors(
                containerColor = FocusColors.SageDeep,
                contentColor   = FocusColors.Ivory
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            Text(
                "Begin $selectedDuration-min Session  →",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ─── Active Launcher Screen ───────────────────────────────────────────────────

@Composable
fun WorkModeLauncherScreen(
    workModeState: WorkModeState,
    onSessionEnd: () -> Unit,
    onEmergencyExit: () -> Unit
) {
    val context = LocalContext.current
    var showEmergencyDialog by remember { mutableStateOf(false) }
    var remainingMs by remember { mutableStateOf(workModeState.remainingMillis) }

    // Countdown ticker
    LaunchedEffect(workModeState.endTimeMillis) {
        while (true) {
            remainingMs = (workModeState.endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingMs <= 0L) {
                onSessionEnd()
                break
            }
            delay(1000)
        }
    }

    // Timer ring animation
    val ringAnim = rememberInfiniteTransition(label = "ring")
    val ringRotation by ringAnim.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing)),
        label         = "rotation"
    )

    val pm = context.packageManager
    val allowedApps = workModeState.allowedApps.mapNotNull { pkg ->
        try {
            AppInfo(
                packageName = pkg,
                label       = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(),
                icon        = pm.getApplicationIcon(pkg)
            )
        } catch (_: Exception) { null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        FocusColors.StoneDeep,
                        Color(0xFF2A2420),
                        FocusColors.StoneDeep
                    )
                )
            )
    ) {

        // Ambient particles
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Subtle grain texture effect
            repeat(20) {
                val x = (it * 37 % size.width.toInt()).toFloat()
                val y = (it * 113 % size.height.toInt()).toFloat()
                drawCircle(
                    color  = FocusColors.SageLight.copy(alpha = 0.03f),
                    radius = it % 3f + 1f,
                    center = Offset(x, y)
                )
            }
        }

        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {

            Spacer(Modifier.height(32.dp))

            // Session label
            Text(
                text  = workModeState.label,
                style = MaterialTheme.typography.titleMedium,
                color = FocusColors.SageLight.copy(alpha = 0.7f)
            )

            // Countdown timer
            Box(contentAlignment = Alignment.Center) {
                // Rotating ring
                Canvas(modifier = Modifier.size(200.dp)) {
                    val totalMs = workModeState.endTimeMillis.let {
                        it - (it - workModeState.remainingMillis)
                    }
                    val progress = if (workModeState.remainingMillis > 0)
                        remainingMs.toFloat() / (workModeState.endTimeMillis - System.currentTimeMillis() + remainingMs).toFloat()
                    else 0f

                    // Background track
                    drawArc(
                        color       = FocusColors.StoneMid,
                        startAngle  = -90f,
                        sweepAngle  = 360f,
                        useCenter   = false,
                        style       = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                    )
                    // Progress arc
                    drawArc(
                        brush       = Brush.sweepGradient(
                            listOf(FocusColors.SageDeep, FocusColors.SageLight)
                        ),
                        startAngle  = -90f,
                        sweepAngle  = 360f * progress,
                        useCenter   = false,
                        style       = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 6.dp.toPx(),
                            cap   = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text  = formatTime(remainingMs),
                        style = MaterialTheme.typography.displayMedium,
                        color = FocusColors.Cream
                    )
                    Text(
                        text  = "remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.StoneLight
                    )
                }
            }

            // Divider
            HorizontalDivider(
                modifier = Modifier.alpha(0.2f),
                color    = FocusColors.StoneLight
            )

            // Allowed apps grid
            Text(
                text  = "ALLOWED APPS",
                style = MaterialTheme.typography.labelLarge,
                color = FocusColors.SageLight.copy(alpha = 0.5f)
            )

            if (allowedApps.isEmpty()) {
                Text(
                    text  = "No apps selected.\nYou're in pure focus mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.StoneLight,
                    textAlign = TextAlign.Center
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement   = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.height(200.dp)
                ) {
                    items(allowedApps) { app ->
                        FocusAppIcon(app = app) {
                            val intent = context.packageManager
                                .getLaunchIntentForPackage(app.packageName)
                            intent?.let { context.startActivity(it) }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Emergency exit button
            TextButton(
                onClick = { showEmergencyDialog = true }
            ) {
                Text(
                    "Emergency Exit",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.StoneLight.copy(alpha = 0.5f)
                )
            }
        }

        // Emergency exit dialog
        if (showEmergencyDialog) {
            EmergencyExitDialog(
                onConfirm  = { onEmergencyExit() },
                onDismiss  = { showEmergencyDialog = false }
            )
        }
    }
}

// ─── Emergency Exit Dialog ────────────────────────────────────────────────────

@Composable
fun EmergencyExitDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Generate random 50-word paragraph on first composition
    val requiredText = remember { generateEmergencyPhrase() }
    var typedText by remember { mutableStateOf("") }
    val isMatch = typedText.trim().equals(requiredText.trim(), ignoreCase = false)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = FocusColors.Cream,
        title = {
            Text(
                "Emergency Exit Protocol",
                style = MaterialTheme.typography.titleLarge,
                color = FocusColors.EarthDeep
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "To exit Work Mode early, type the following text exactly:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.EarthMid
                )
                Card(
                    colors = CardDefaults.cardColors(containerColor = FocusColors.SagePale),
                    shape  = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text     = requiredText,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = FocusColors.EarthDeep,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                OutlinedTextField(
                    value         = typedText,
                    onValueChange = { typedText = it },
                    modifier      = Modifier.fillMaxWidth(),
                    placeholder   = { Text("Type the text above...") },
                    isError       = typedText.isNotEmpty() && !isMatch,
                    shape         = RoundedCornerShape(8.dp),
                    maxLines      = 8
                )
                if (isMatch) {
                    Text(
                        "✓ Text matches. You may exit.",
                        color = FocusColors.SageDeep,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick  = onConfirm,
                enabled  = isMatch,
                colors   = ButtonDefaults.buttonColors(
                    containerColor        = FocusColors.NudgeBlock,
                    disabledContainerColor = FocusColors.Linen
                )
            ) {
                Text("Exit Session")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = FocusColors.EarthMid)
            }
        }
    )
}

// ─── Sub-Components ───────────────────────────────────────────────────────────

@Composable
fun AppSelectionCard(app: AppInfo, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (isSelected) FocusColors.SagePale else FocusColors.Cream
        ),
        border    = if (isSelected)
            BorderStroke(2.dp, FocusColors.SageDeep)
        else
            BorderStroke(1.dp, FocusColors.Linen)
    ) {
        Row(
            modifier            = Modifier.padding(12.dp),
            verticalAlignment   = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isSelected) {
                Text("✓", color = FocusColors.SageDeep, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text  = app.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) FocusColors.SageDeep else FocusColors.EarthMid,
                maxLines = 1
            )
        }
    }
}

@Composable
fun FocusAppIcon(app: AppInfo, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier            = Modifier.clickable(onClick = onClick)
    ) {
        Card(
            shape  = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = FocusColors.StoneMid)
        ) {
            Box(
                modifier          = Modifier.size(56.dp).padding(8.dp),
                contentAlignment  = Alignment.Center
            ) {
                Text(
                    text  = app.label.take(2).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = FocusColors.SageLight
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text     = app.label,
            style    = MaterialTheme.typography.bodySmall,
            color    = FocusColors.StoneLight,
            maxLines = 1
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours   = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

fun generateEmergencyPhrase(): String {
    val phrases = listOf(
        "The capacity to focus deeply is a superpower. By choosing to exit early today, I acknowledge that my mind seeks distraction. I return to the present moment with full awareness and recommit to building the discipline that transforms ordinary days into extraordinary ones.",
        "Every time I resist the urge to exit early, I strengthen the neural pathways of focus and self-discipline. This session is an investment in the person I am becoming. The discomfort I feel is not a sign to stop; it is the sensation of growth taking root within me.",
        "Leaving a session before it ends is a choice I make consciously. The temporary relief of distraction will cost me the long-term reward of deep work. I understand this trade-off fully. I accept it with awareness and choose to proceed with my eyes open and my intentions clear."
    )
    return phrases.random()
}

// ─── Data class for app info ──────────────────────────────────────────────────

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable? = null
)
