package com.focusgate.utils

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import com.focusgate.services.FocusAccessibilityService
import com.focusgate.services.FocusNotificationListenerService

/**
 * Checks all required permissions for FocusGate to function.
 */
class PermissionHelper(private val context: Context) {

    enum class PermissionType {
        OVERLAY,
        ACCESSIBILITY,
        USAGE_STATS,
        NOTIFICATION_LISTENER,
        BATTERY_OPTIMIZATION
    }

    data class MissingPermission(
        val type: PermissionType,
        val title: String,
        val description: String,
        val isRequired: Boolean
    )

    fun getMissingPermissions(): List<MissingPermission> {
        val missing = mutableListOf<MissingPermission>()

        if (!hasOverlayPermission()) {
            missing.add(MissingPermission(
                PermissionType.OVERLAY,
                "Draw Over Other Apps",
                "Required to show the Intent Gate and nudge overlays",
                true
            ))
        }

        if (!hasAccessibilityPermission()) {
            missing.add(MissingPermission(
                PermissionType.ACCESSIBILITY,
                "Accessibility Service",
                "Required to monitor foreground apps and enforce Work Mode",
                true
            ))
        }

        if (!hasUsageStatsPermission()) {
            missing.add(MissingPermission(
                PermissionType.USAGE_STATS,
                "Usage Statistics",
                "Required to detect which app is currently in the foreground",
                true
            ))
        }

        if (!hasNotificationListenerPermission()) {
            missing.add(MissingPermission(
                PermissionType.NOTIFICATION_LISTENER,
                "Notification Access",
                "Required to capture and summarize notifications during Work Mode",
                false
            ))
        }

        if (!isBatteryOptimizationIgnored()) {
            missing.add(MissingPermission(
                PermissionType.BATTERY_OPTIMIZATION,
                "Battery Optimization",
                "Recommended to prevent Android from killing FocusGate",
                false
            ))
        }

        return missing
    }

    fun hasAllRequired(): Boolean = getMissingPermissions().none { it.isRequired }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    private fun hasAccessibilityPermission(): Boolean {
        val service = "${context.packageName}/${FocusAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(service, ignoreCase = true) }
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun hasNotificationListenerPermission(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        val component = ComponentName(context, FocusNotificationListenerService::class.java)
        return flat.contains(component.flattenToString())
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
