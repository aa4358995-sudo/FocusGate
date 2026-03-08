package com.focusgate.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── FocusGate Zen Color Palette ─────────────────────────────────────────────
//
// Design philosophy: Calming, earthy, and intentional.
// Evokes the feeling of a quiet morning in a bamboo forest.
// NO aggressive reds, NO bright blues, NO high-contrast jarring combinations.

object FocusColors {

    // ── Sage & Earth Greens ──────────────────────────────────────────────────
    val SageDeep        = Color(0xFF4A6741)   // Deep sage – primary actions
    val SageMid         = Color(0xFF6B8F5E)   // Mid sage – secondary elements
    val SageLight       = Color(0xFF9DBF8F)   // Light sage – accents, highlights
    val SagePale        = Color(0xFFD4E8CC)   // Pale sage – backgrounds, cards
    val SageMist        = Color(0xFFECF4E7)   // Misty sage – page backgrounds

    // ── Off-Whites & Creams ──────────────────────────────────────────────────
    val Ivory           = Color(0xFFFAF8F4)   // Main background
    val Cream           = Color(0xFFF3EFE7)   // Card backgrounds
    val Linen           = Color(0xFFE8E0D3)   // Dividers, borders

    // ── Warm Earthen Browns ──────────────────────────────────────────────────
    val EarthDeep       = Color(0xFF5C4033)   // Deep earth – body text
    val EarthMid        = Color(0xFF7D5A4A)   // Mid earth – secondary text
    val EarthLight      = Color(0xFFB08070)   // Light earth – placeholder text

    // ── Clay & Terracotta (accent) ───────────────────────────────────────────
    val ClayWarm        = Color(0xFFB87355)   // Warm clay – CTA buttons
    val ClayMuted       = Color(0xFFD4A485)   // Muted clay – secondary CTAs

    // ── Stone Grays ──────────────────────────────────────────────────────────
    val StoneDeep       = Color(0xFF3D3530)   // Darkest stone – dark mode bg
    val StoneMid        = Color(0xFF5A524D)   // Mid stone – dark mode surface
    val StoneLight      = Color(0xFF7A726D)   // Light stone – dark mode text

    // ── Focus State Colors ───────────────────────────────────────────────────
    val FocusActive     = SageDeep            // Timer, active session indicator
    val NudgeWarning    = Color(0xFFB8934A)   // Warm amber – Level 1 nudge
    val NudgeGrayscale  = Color(0xFF808080)   // Gray – Level 2 nudge indicator
    val NudgeBlock      = Color(0xFF8B5E52)   // Muted terracotta – Level 3 nudge (NOT red)

    // ── Transparent Overlays ─────────────────────────────────────────────────
    val OverlayLight    = Color(0xCCFAF8F4)   // 80% ivory overlay
    val OverlayDark     = Color(0xCC1A1614)   // 80% dark overlay
}

// ─── Color Schemes ────────────────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary             = FocusColors.SageDeep,
    onPrimary           = FocusColors.Ivory,
    primaryContainer    = FocusColors.SagePale,
    onPrimaryContainer  = FocusColors.SageDeep,
    secondary           = FocusColors.ClayWarm,
    onSecondary         = FocusColors.Ivory,
    secondaryContainer  = FocusColors.Linen,
    onSecondaryContainer= FocusColors.EarthDeep,
    background          = FocusColors.Ivory,
    onBackground        = FocusColors.EarthDeep,
    surface             = FocusColors.Cream,
    onSurface           = FocusColors.EarthDeep,
    surfaceVariant      = FocusColors.Linen,
    onSurfaceVariant    = FocusColors.EarthMid,
    outline             = FocusColors.Linen,
    outlineVariant      = FocusColors.SagePale,
    error               = FocusColors.NudgeBlock,
    onError             = FocusColors.Ivory,
)

private val DarkColorScheme = darkColorScheme(
    primary             = FocusColors.SageLight,
    onPrimary           = FocusColors.StoneDeep,
    primaryContainer    = FocusColors.SageMid,
    onPrimaryContainer  = FocusColors.SageMist,
    secondary           = FocusColors.ClayMuted,
    onSecondary         = FocusColors.StoneDeep,
    background          = FocusColors.StoneDeep,
    onBackground        = FocusColors.Cream,
    surface             = FocusColors.StoneMid,
    onSurface           = FocusColors.Cream,
    surfaceVariant      = Color(0xFF4A4240),
    onSurfaceVariant    = FocusColors.Linen,
    outline             = FocusColors.StoneLight,
    error               = FocusColors.NudgeBlock,
)

// ─── Typography ───────────────────────────────────────────────────────────────
//
// Using system fonts since custom font files need to be bundled separately.
// In production, replace with Freight Text (body) + Canela (display) for
// a truly zen editorial feel.

val FocusTypography = androidx.compose.material3.Typography(
    displayLarge  = TextStyle(
        fontFamily  = FontFamily.Serif,
        fontWeight  = FontWeight.Light,
        fontSize    = 57.sp,
        lineHeight  = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily  = FontFamily.Serif,
        fontWeight  = FontWeight.Light,
        fontSize    = 45.sp,
        lineHeight  = 52.sp
    ),
    headlineLarge = TextStyle(
        fontFamily  = FontFamily.Serif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 32.sp,
        lineHeight  = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily  = FontFamily.Serif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 28.sp,
        lineHeight  = 36.sp
    ),
    titleLarge    = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Medium,
        fontSize    = 22.sp,
        lineHeight  = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium   = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Medium,
        fontSize    = 16.sp,
        lineHeight  = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge     = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 16.sp,
        lineHeight  = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium    = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall     = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Normal,
        fontSize    = 12.sp,
        lineHeight  = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge    = TextStyle(
        fontFamily  = FontFamily.SansSerif,
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        lineHeight  = 20.sp,
        letterSpacing = 0.1.sp
    )
)

// ─── Composable Theme Wrapper ─────────────────────────────────────────────────

@Composable
fun FocusGateTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography  = FocusTypography,
        content     = content
    )
}
