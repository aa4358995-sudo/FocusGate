package com.focusgate.app.ui.digest

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import com.focusgate.app.FocusGateApp
import com.focusgate.app.MainActivity
import com.focusgate.app.services.DigestResult
import com.focusgate.app.services.NotificationInterceptorService
import com.focusgate.app.ui.theme.FocusColors
import com.focusgate.app.ui.theme.FocusGateTheme
import kotlinx.coroutines.launch

/**
 * PostFocusDigestActivity
 * ────────────────────────
 * The reward screen shown after a successful Work Mode session.
 *
 * Displays:
 *  1. Congratulations banner with session stats
 *  2. AI-generated notification summary ("What you missed")
 *  3. Individual notification breakdown by app
 *  4. Call-to-action to continue being mindful
 *
 * Design: Warm, celebratory but calm. Uses gold/sage accents.
 * No harsh congratulation. Gentle acknowledgment.
 */
class PostFocusDigestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stateManager = (application as FocusGateApp).focusStateManager

        setContent {
            FocusGateTheme {
                var digestResult by remember { mutableStateOf<DigestResult?>(null) }
                var isLoading    by remember { mutableStateOf(true) }

                // Generate digest on first composition
                LaunchedEffect(Unit) {
                    lifecycleScope.launch {
                        try {
                            val service = NotificationInterceptorService.getInstance()
                            digestResult = service?.generateDigest()
                                ?: DigestResult(
                                    summary     = "Your focus session is complete. No notifications were intercepted.",
                                    items       = emptyList(),
                                    urgentCount = 0,
                                    totalCount  = 0
                                )
                        } catch (e: Exception) {
                            digestResult = DigestResult(
                                summary     = "Session complete. Check your notifications when ready.",
                                items       = emptyList(),
                                urgentCount = 0,
                                totalCount  = 0
                            )
                        } finally {
                            isLoading = false
                        }
                    }
                }

                PostFocusDigestScreen(
                    digestResult = digestResult,
                    isLoading    = isLoading,
                    onContinue   = {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                        )
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun PostFocusDigestScreen(
    digestResult: DigestResult?,
    isLoading: Boolean,
    onContinue: () -> Unit
) {
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (!isLoading) {
            kotlinx.coroutines.delay(200)
            contentVisible = true
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.Ivory)
    ) {
        // Background gradient
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        FocusColors.SageMist,
                        FocusColors.Ivory,
                        FocusColors.Cream
                    )
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            // Achievement glyph
            AnimatedVisibility(
                visible = contentVisible,
                enter   = scaleIn(spring(dampingRatio = 0.6f)) + fadeIn()
            ) {
                Box(
                    modifier          = Modifier
                        .size(96.dp)
                        .background(FocusColors.SagePale, CircleShape),
                    contentAlignment  = Alignment.Center
                ) {
                    Text("✦", style = MaterialTheme.typography.displayMedium,
                         color = FocusColors.SageDeep)
                }
            }

            // Header
            AnimatedVisibility(visible = contentVisible, enter = fadeIn(tween(400, 200))) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text      = "Session Complete",
                        style     = MaterialTheme.typography.headlineLarge,
                        color     = FocusColors.EarthDeep,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text      = "You stayed focused. Here's what you missed.",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = FocusColors.EarthMid,
                        textAlign = TextAlign.Center
                    )
                }
            }

            HorizontalDivider(color = FocusColors.Linen)

            // Loading state
            if (isLoading) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color    = FocusColors.SageDeep,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "Summarizing your notifications with AI...",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.EarthLight
                    )
                }
            }

            // Digest content
            digestResult?.let { result ->
                AnimatedVisibility(visible = contentVisible, enter = fadeIn(tween(600, 400))) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                        // Stats row
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatCard(
                                modifier = Modifier.weight(1f),
                                label    = "Total",
                                value    = result.totalCount.toString(),
                                unit     = "notifications"
                            )
                            StatCard(
                                modifier = Modifier.weight(1f),
                                label    = "Urgent",
                                value    = result.urgentCount.toString(),
                                unit     = "to review"
                            )
                        }

                        // AI Summary card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape    = RoundedCornerShape(16.dp),
                            colors   = CardDefaults.cardColors(containerColor = FocusColors.Cream),
                            elevation = CardDefaults.cardElevation(0.dp),
                            border   = BorderStroke(1.dp, FocusColors.Linen)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("✦", color = FocusColors.SageDeep)
                                    Text(
                                        "AI Summary",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = FocusColors.EarthDeep
                                    )
                                }
                                Text(
                                    text  = result.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = FocusColors.EarthMid
                                )
                            }
                        }

                        // Individual notifications (if any)
                        if (result.items.isNotEmpty()) {
                            Text(
                                "Individual Notifications",
                                style = MaterialTheme.typography.titleMedium,
                                color = FocusColors.EarthDeep
                            )

                            result.items
                                .groupBy { it.appName }
                                .forEach { (appName, notifs) ->
                                    NotificationGroup(
                                        appName = appName,
                                        count   = notifs.size,
                                        preview = notifs.firstOrNull()?.title ?: ""
                                    )
                                }
                        } else {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape    = RoundedCornerShape(16.dp),
                                colors   = CardDefaults.cardColors(containerColor = FocusColors.SagePale)
                            ) {
                                Text(
                                    "🌿  No notifications were received during your session.",
                                    style     = MaterialTheme.typography.bodyMedium,
                                    color     = FocusColors.SageDeep,
                                    modifier  = Modifier.padding(20.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // CTA button
            AnimatedVisibility(visible = contentVisible && !isLoading, enter = fadeIn(tween(800, 600))) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick   = onContinue,
                        modifier  = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape     = RoundedCornerShape(16.dp),
                        colors    = ButtonDefaults.buttonColors(
                            containerColor = FocusColors.SageDeep,
                            contentColor   = FocusColors.Ivory
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text("Return to dashboard  →", style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        "Stay intentional. Every session counts.",
                        style     = MaterialTheme.typography.bodySmall,
                        color     = FocusColors.EarthLight,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun StatCard(modifier: Modifier = Modifier, label: String, value: String, unit: String) {
    Card(
        modifier  = modifier,
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = FocusColors.Cream),
        border    = BorderStroke(1.dp, FocusColors.Linen)
    ) {
        Column(
            modifier            = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium, color = FocusColors.SageDeep)
            Text(unit,  style = MaterialTheme.typography.bodySmall, color = FocusColors.EarthLight)
            Text(label, style = MaterialTheme.typography.labelLarge, color = FocusColors.EarthMid)
        }
    }
}

@Composable
fun NotificationGroup(appName: String, count: Int, preview: String) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = FocusColors.Cream),
        border    = BorderStroke(1.dp, FocusColors.Linen)
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            // App initial circle
            Box(
                modifier          = Modifier
                    .size(40.dp)
                    .background(FocusColors.SagePale, CircleShape),
                contentAlignment  = Alignment.Center
            ) {
                Text(
                    appName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = FocusColors.SageDeep
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(appName, style = MaterialTheme.typography.titleMedium, color = FocusColors.EarthDeep)
                if (preview.isNotBlank()) {
                    Text(
                        preview.take(60),
                        style   = MaterialTheme.typography.bodySmall,
                        color   = FocusColors.EarthLight,
                        maxLines = 1
                    )
                }
            }
            Badge(containerColor = FocusColors.SagePale, contentColor = FocusColors.SageDeep) {
                Text(count.toString())
            }
        }
    }
}
