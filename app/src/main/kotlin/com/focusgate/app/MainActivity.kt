package com.focusgate.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.focusgate.app.state.FocusStateManager
import com.focusgate.app.ui.onboarding.OnboardingActivity
import com.focusgate.app.ui.theme.FocusColors
import com.focusgate.app.ui.theme.FocusGateTheme
import com.focusgate.app.ui.workmode.WorkModeActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * MainActivity
 * ─────────────
 * The FocusGate Dashboard.
 *
 * Tabs:
 *  1. Home:       Live session status, quick-start Work Mode
 *  2. Gate:       Intent Gate settings, exception app management
 *  3. Stats:      Focus streak, total sessions, minutes focused
 *  4. Settings:   API key, permissions status, toggles
 */
class MainActivity : ComponentActivity() {

    private lateinit var stateManager: FocusStateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stateManager = (application as FocusGateApp).focusStateManager

        setContent {
            FocusGateTheme {
                val onboardingDone by stateManager.onboardingComplete.collectAsState(initial = false)

                if (!onboardingDone) {
                    OnboardingGate(
                        onComplete = {
                            stateManager.setOnboardingComplete()
                        }
                    )
                } else {
                    DashboardScreen(stateManager = stateManager)
                }
            }
        }
    }
}

// ─── Dashboard ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(stateManager: FocusStateManager) {
    val context          = LocalContext.current
    val workModeState    by stateManager.workModeState.collectAsState(
        initial = com.focusgate.app.state.WorkModeState(false, 0, emptyList(), "")
    )
    val gateEnabled      by stateManager.isGateEnabled.collectAsState(initial = true)
    val focusStats       by stateManager.focusStats.collectAsState(
        initial = com.focusgate.app.state.FocusStats(0, 0, 0)
    )
    val currentIntent    by stateManager.currentIntent.collectAsState(initial = null)
    val scope            = rememberCoroutineScope()

    val permissions = remember { PermissionChecker(context) }
    var selectedTab  by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = FocusColors.Ivory,
        bottomBar = {
            NavigationBar(
                containerColor = FocusColors.Cream,
                contentColor   = FocusColors.EarthMid,
                tonalElevation = 0.dp
            ) {
                listOf("Home", "Gate", "Stats", "Setup").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected  = selectedTab == index,
                        onClick   = { selectedTab = index },
                        icon      = {
                            Text(
                                listOf("⌁", "◈", "◎", "⚙")[index],
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        label     = {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        },
                        colors    = NavigationBarItemDefaults.colors(
                            selectedIconColor    = FocusColors.SageDeep,
                            selectedTextColor    = FocusColors.SageDeep,
                            indicatorColor       = FocusColors.SagePale,
                            unselectedIconColor  = FocusColors.EarthLight,
                            unselectedTextColor  = FocusColors.EarthLight
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> HomeTab(
                    workModeState = workModeState,
                    currentIntent = currentIntent,
                    gateEnabled   = gateEnabled,
                    focusStats    = focusStats,
                    onStartWorkMode = {
                        context.startActivity(WorkModeActivity.createIntent(context))
                    },
                    onToggleGate = { stateManager.setGateEnabled(!gateEnabled) }
                )
                1 -> GateTab(stateManager = stateManager)
                2 -> StatsTab(focusStats = focusStats)
                3 -> SetupTab(permissions = permissions, stateManager = stateManager)
            }
        }
    }
}

// ─── Home Tab ─────────────────────────────────────────────────────────────────

@Composable
fun HomeTab(
    workModeState: com.focusgate.app.state.WorkModeState,
    currentIntent: String?,
    gateEnabled: Boolean,
    focusStats: com.focusgate.app.state.FocusStats,
    onStartWorkMode: () -> Unit,
    onToggleGate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        // Greeting
        Text(
            text  = "FocusGate",
            style = MaterialTheme.typography.headlineLarge,
            color = FocusColors.EarthDeep
        )
        Text(
            text  = "Your mindful phone companion.",
            style = MaterialTheme.typography.bodyMedium,
            color = FocusColors.EarthLight
        )

        // Work Mode card
        Card(
            modifier  = Modifier.fillMaxWidth(),
            shape     = RoundedCornerShape(20.dp),
            colors    = CardDefaults.cardColors(
                containerColor = if (workModeState.isActive) FocusColors.SageDeep
                                 else FocusColors.Cream
            ),
            border    = if (!workModeState.isActive)
                BorderStroke(1.dp, FocusColors.Linen)
            else null
        ) {
            Column(
                modifier  = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (workModeState.isActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                "Work Mode Active",
                                style = MaterialTheme.typography.titleLarge,
                                color = FocusColors.Ivory
                            )
                            Text(
                                workModeState.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = FocusColors.SageLight
                            )
                        }
                        Text(
                            com.focusgate.app.ui.workmode.formatTime(workModeState.remainingMillis),
                            style = MaterialTheme.typography.headlineMedium,
                            color = FocusColors.Ivory
                        )
                    }
                } else {
                    Text("Start Focus Session", style = MaterialTheme.typography.titleLarge,
                         color = FocusColors.EarthDeep)
                    Text("Block distractions and enter deep work.",
                         style = MaterialTheme.typography.bodySmall, color = FocusColors.EarthLight)
                    Button(
                        onClick   = onStartWorkMode,
                        modifier  = Modifier.fillMaxWidth().height(48.dp),
                        shape     = RoundedCornerShape(12.dp),
                        colors    = ButtonDefaults.buttonColors(containerColor = FocusColors.SageDeep),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text("Begin Work Mode", color = FocusColors.Ivory)
                    }
                }
            }
        }

        // Intent Gate toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(20.dp),
            colors   = CardDefaults.cardColors(containerColor = FocusColors.Cream),
            border   = BorderStroke(1.dp, FocusColors.Linen)
        ) {
            Row(
                modifier              = Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Intent Gate", style = MaterialTheme.typography.titleMedium,
                         color = FocusColors.EarthDeep)
                    Text("Prompt for intent on every unlock",
                         style = MaterialTheme.typography.bodySmall, color = FocusColors.EarthLight)
                }
                Switch(
                    checked   = gateEnabled,
                    onCheckedChange = { onToggleGate() },
                    colors    = SwitchDefaults.colors(
                        checkedThumbColor  = FocusColors.Ivory,
                        checkedTrackColor  = FocusColors.SageDeep,
                        uncheckedThumbColor = FocusColors.Linen,
                        uncheckedTrackColor = FocusColors.EarthLight
                    )
                )
            }
        }

        // Current intent
        currentIntent?.let { intent ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(20.dp),
                colors   = CardDefaults.cardColors(containerColor = FocusColors.SagePale),
                border   = BorderStroke(1.dp, FocusColors.SageLight.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Current Intent", style = MaterialTheme.typography.labelLarge,
                         color = FocusColors.SageDeep)
                    Text("\"$intent\"", style = MaterialTheme.typography.bodyLarge,
                         color = FocusColors.EarthDeep)
                }
            }
        }

        // Quick stats
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QuickStat("🔥", focusStats.streakDays.toString(), "day streak",
                      modifier = Modifier.weight(1f))
            QuickStat("⌛", focusStats.sessionsCompleted.toString(), "sessions",
                      modifier = Modifier.weight(1f))
            QuickStat("◎", focusStats.minutesFocused.toString(), "minutes",
                      modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun QuickStat(emoji: String, value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = FocusColors.Cream),
        border   = BorderStroke(1.dp, FocusColors.Linen)
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Text(value, style = MaterialTheme.typography.titleLarge, color = FocusColors.SageDeep)
            Text(label, style = MaterialTheme.typography.bodySmall, color = FocusColors.EarthLight)
        }
    }
}

// ─── Gate Tab (Intent Gate Settings) ─────────────────────────────────────────

@Composable
fun GateTab(stateManager: FocusStateManager) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Intent Gate", style = MaterialTheme.typography.headlineLarge, color = FocusColors.EarthDeep)
        Text(
            "The gate intercepts every phone unlock and asks you to declare your intent. AI monitors whether you follow through.",
            style = MaterialTheme.typography.bodyMedium,
            color = FocusColors.EarthMid
        )

        HorizontalDivider(color = FocusColors.Linen)

        // How it works
        Text("How it works", style = MaterialTheme.typography.titleLarge, color = FocusColors.EarthDeep)
        listOf(
            "1. Unlock" to "A full-screen prompt appears asking why you're opening your phone.",
            "2. Declare" to "You type your specific intent: \"Check work email\", \"Study Python\", etc.",
            "3. Monitor" to "AI watches which apps you open. If they mismatch your intent, nudges escalate.",
            "4. Nudge" to "Level 1: Reminder. Level 2: Grayscale. Level 3: App blocked."
        ).forEach { (title, desc) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(containerColor = FocusColors.Cream),
                border   = BorderStroke(1.dp, FocusColors.Linen)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = FocusColors.SageDeep)
                    Text(desc,  style = MaterialTheme.typography.bodySmall,   color = FocusColors.EarthMid)
                }
            }
        }
    }
}

// ─── Stats Tab ────────────────────────────────────────────────────────────────

@Composable
fun StatsTab(focusStats: com.focusgate.app.state.FocusStats) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Your Journey", style = MaterialTheme.typography.headlineLarge, color = FocusColors.EarthDeep)

        // Big streak display
        Box(
            modifier          = Modifier
                .size(160.dp)
                .background(FocusColors.SagePale, CircleShape),
            contentAlignment  = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔥", style = MaterialTheme.typography.displayLarge)
                Text(
                    focusStats.streakDays.toString(),
                    style = MaterialTheme.typography.headlineLarge,
                    color = FocusColors.SageDeep
                )
                Text("day streak", style = MaterialTheme.typography.bodySmall, color = FocusColors.EarthLight)
            }
        }

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            com.focusgate.app.ui.digest.StatCard(
                modifier = Modifier.weight(1f),
                label    = "Sessions",
                value    = focusStats.sessionsCompleted.toString(),
                unit     = "completed"
            )
            com.focusgate.app.ui.digest.StatCard(
                modifier = Modifier.weight(1f),
                label    = "Focused",
                value    = focusStats.minutesFocused.toString(),
                unit     = "minutes"
            )
        }

        Text(
            "Every focused session is a vote for the person you're becoming.",
            style     = MaterialTheme.typography.bodyMedium,
            color     = FocusColors.EarthLight,
            textAlign = TextAlign.Center
        )
    }
}

// ─── Setup Tab ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupTab(permissions: PermissionChecker, stateManager: FocusStateManager) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var apiKey  by remember { mutableStateOf("") }
    var apiKeySaved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        stateManager.geminiApiKey.collect { key ->
            if (!key.isNullOrBlank()) apiKey = key.take(8) + "••••••••"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Setup & Permissions", style = MaterialTheme.typography.headlineLarge,
             color = FocusColors.EarthDeep)

        // Required permissions
        Text("Required Permissions", style = MaterialTheme.typography.titleLarge,
             color = FocusColors.EarthDeep)

        PermissionCard(
            title    = "Accessibility Service",
            desc     = "Core: monitors foreground apps and applies nudges",
            granted  = permissions.isAccessibilityEnabled(),
            onGrant  = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        PermissionCard(
            title    = "Notification Listener",
            desc     = "Intercepts notifications during Work Mode",
            granted  = permissions.isNotificationListenerEnabled(),
            onGrant  = {
                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }
        )

        PermissionCard(
            title    = "Draw Over Apps",
            desc     = "Required for Intent Gate overlay",
            granted  = Settings.canDrawOverlays(context),
            onGrant  = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                           Uri.parse("package:${context.packageName}"))
                )
            }
        )

        PermissionCard(
            title    = "Usage Access",
            desc     = "Detects which app is in the foreground",
            granted  = permissions.isUsageStatsEnabled(),
            onGrant  = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        )

        HorizontalDivider(color = FocusColors.Linen)

        // Gemini API Key
        Text("Gemini AI Integration", style = MaterialTheme.typography.titleLarge,
             color = FocusColors.EarthDeep)
        Text(
            "Required for intent matching and notification summarization. Get a free key at ai.google.dev",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.EarthLight
        )
        OutlinedTextField(
            value         = apiKey,
            onValueChange = { apiKey = it; apiKeySaved = false },
            modifier      = Modifier.fillMaxWidth(),
            label         = { Text("Gemini API Key") },
            placeholder   = { Text("AIzaSy...") },
            shape         = RoundedCornerShape(12.dp),
            colors        = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = FocusColors.SageDeep,
                unfocusedBorderColor = FocusColors.Linen,
                focusedContainerColor   = FocusColors.Cream,
                unfocusedContainerColor = FocusColors.Cream
            ),
            singleLine    = true,
            trailingIcon  = if (apiKeySaved) ({
                Text("✓", color = FocusColors.SageDeep,
                     style = MaterialTheme.typography.titleMedium,
                     modifier = Modifier.padding(end = 12.dp))
            }) else null
        )
        Button(
            onClick = {
                stateManager.saveGeminiApiKey(apiKey.trim())
                apiKeySaved = true
            },
            modifier  = Modifier.fillMaxWidth().height(48.dp),
            shape     = RoundedCornerShape(12.dp),
            colors    = ButtonDefaults.buttonColors(containerColor = FocusColors.SageDeep),
            elevation = ButtonDefaults.buttonElevation(0.dp)
        ) {
            Text("Save API Key")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PermissionCard(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (granted) FocusColors.SagePale else FocusColors.Cream
        ),
        border    = BorderStroke(
            1.dp,
            if (granted) FocusColors.SageLight.copy(alpha = 0.5f) else FocusColors.Linen
        )
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                if (granted) "✓" else "○",
                style = MaterialTheme.typography.titleLarge,
                color = if (granted) FocusColors.SageDeep else FocusColors.EarthLight
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                     color = if (granted) FocusColors.SageDeep else FocusColors.EarthDeep)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = FocusColors.EarthLight)
            }
            if (!granted) {
                TextButton(onClick = onGrant) {
                    Text("Grant", color = FocusColors.SageDeep)
                }
            }
        }
    }
}

// ─── Onboarding Gate ─────────────────────────────────────────────────────────

@Composable
fun OnboardingGate(onComplete: () -> Unit) {
    var page by remember { mutableStateOf(0) }

    val pages = listOf(
        Triple("⌁", "Welcome to FocusGate", "Your mindful phone guardian. FocusGate helps you break the cycle of mindless scrolling through intentional prompts, AI monitoring, and structured focus sessions."),
        Triple("◈", "The Intent Gate", "Every time you unlock your phone, you'll declare your purpose. This simple act of intentionality dramatically reduces aimless scrolling."),
        Triple("◎", "Work Mode", "Enter deep focus sessions with a curated app whitelist. The system blocks everything else and collects notifications for an AI-powered digest afterward."),
        Triple("✦", "Gentle Nudges", "If you stray from your intent, FocusGate escalates: a reminder, then grayscale, then a block. Respectful, not punitive.")
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.Ivory),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier            = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Spacer(Modifier.weight(1f))

            AnimatedContent(targetState = page, label = "onboarding") { p ->
                val (icon, title, desc) = pages[p]
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(icon, style = MaterialTheme.typography.displayMedium, color = FocusColors.SageDeep)
                    Text(title, style = MaterialTheme.typography.headlineMedium,
                         color = FocusColors.EarthDeep, textAlign = TextAlign.Center)
                    Text(desc, style = MaterialTheme.typography.bodyMedium,
                         color = FocusColors.EarthMid, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.weight(1f))

            // Page dots
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { i ->
                    Box(
                        modifier = Modifier
                            .size(if (i == page) 24.dp else 8.dp, 8.dp)
                            .background(
                                if (i == page) FocusColors.SageDeep else FocusColors.Linen,
                                RoundedCornerShape(4.dp)
                            )
                    )
                }
            }

            Button(
                onClick = {
                    if (page < pages.lastIndex) page++ else onComplete()
                },
                modifier  = Modifier.fillMaxWidth().height(54.dp),
                shape     = RoundedCornerShape(16.dp),
                colors    = ButtonDefaults.buttonColors(containerColor = FocusColors.SageDeep),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                Text(if (page < pages.lastIndex) "Continue →" else "Begin Journey",
                     style = MaterialTheme.typography.titleMedium, color = FocusColors.Ivory)
            }
        }
    }
}

// ─── Permission Checker ───────────────────────────────────────────────────────

class PermissionChecker(private val context: Context) {
    fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    fun isUsageStatsEnabled(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }
}
