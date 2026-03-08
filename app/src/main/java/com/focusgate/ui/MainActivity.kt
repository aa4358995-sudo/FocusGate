package com.focusgate.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusgate.FocusGateApplication
import com.focusgate.R
import com.focusgate.data.PreferencesManager
import com.focusgate.databinding.ActivityMainBinding
import com.focusgate.services.AppMonitorService
import com.focusgate.services.FocusAccessibilityService
import com.focusgate.services.FocusNotificationListenerService
import com.focusgate.utils.PermissionHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * MainActivity — The Garden
 *
 * The central hub of FocusGate. Provides:
 * - System status overview (permissions, active services)
 * - Work Mode setup and launch
 * - Quick stats (streak, total focus time)
 * - Settings access
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var permissionHelper: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = FocusGateApplication.instance.preferencesManager
        permissionHelper = PermissionHelper(this)

        setupUI()
        observeState()
        startMonitorService()

        // Handle deep-link into Work Mode setup
        if (intent.getBooleanExtra("START_WORK_MODE_SETUP", false)) {
            showWorkModeSetup()
        }
    }

    private fun setupUI() {
        binding.apply {
            // Work Mode card
            cardStartWorkMode.setOnClickListener { showWorkModeSetup() }
            btnStartWorkMode.setOnClickListener { showWorkModeSetup() }

            // Gate toggle
            switchGate.setOnCheckedChangeListener { _, isChecked ->
                lifecycleScope.launch {
                    prefs.setGateEnabled(isChecked)
                    val msg = if (isChecked) "Intent Gate enabled" else "Intent Gate disabled"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }

            // Permissions row
            btnGrantPermissions.setOnClickListener { checkAndRequestPermissions() }

            // Settings
            btnSettings.setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }

            // History
            btnHistory.setOnClickListener {
                // TODO: launch HistoryActivity
                Toast.makeText(this@MainActivity, "Coming soon", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            prefs.isGateEnabled.collect { enabled ->
                binding.switchGate.isChecked = enabled
                binding.tvGateStatus.text = if (enabled) "Active" else "Paused"
                binding.tvGateStatus.setTextColor(
                    getColor(if (enabled) R.color.sage_green_dark else R.color.text_secondary)
                )
            }
        }

        lifecycleScope.launch {
            prefs.isWorkModeActive.collect { active ->
                binding.cardWorkModeActive.visibility = if (active) View.VISIBLE else View.GONE
                binding.cardStartWorkMode.visibility = if (active) View.GONE else View.VISIBLE

                if (active) {
                    binding.btnResumeWorkMode.setOnClickListener {
                        startActivity(Intent(this@MainActivity, WorkModeActivity::class.java))
                    }
                }
            }
        }

        lifecycleScope.launch {
            combine(
                prefs.totalFocusMinutes,
                prefs.totalGatesPassed,
                prefs.streakDays
            ) { minutes, gates, streak ->
                Triple(minutes, gates, streak)
            }.collect { (minutes, gates, streak) ->
                binding.tvFocusTime.text = formatMinutes(minutes)
                binding.tvGateCount.text = "$gates"
                binding.tvStreak.text = "${streak}d"
            }
        }
    }

    private fun showWorkModeSetup() {
        val dialog = WorkModeSetupBottomSheet.newInstance()
        dialog.onConfirm = { durationMinutes, whitelist, intent ->
            launchWorkMode(durationMinutes, whitelist, intent)
        }
        dialog.show(supportFragmentManager, "WorkModeSetup")
    }

    private fun launchWorkMode(durationMinutes: Int, whitelist: Set<String>, userIntent: String) {
        val workIntent = Intent(this, WorkModeActivity::class.java)
        startActivity(workIntent)

        // We need a slight delay for the activity to initialize before calling activateWorkMode
        binding.root.postDelayed({
            // The WorkModeActivity will check prefs and find it not yet active,
            // then redirect back to us — so we need to activate from here
            lifecycleScope.launch {
                val activity = WorkModeActivity()
                // Actually set up work mode via prefs directly
                com.focusgate.utils.EmergencyPhraseGenerator.generate().let { phrase ->
                    val endTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
                    prefs.startWorkMode(endTime, whitelist, phrase)
                }
            }
        }, 100)
    }

    private fun checkAndRequestPermissions() {
        val issues = permissionHelper.getMissingPermissions()
        if (issues.isEmpty()) {
            Toast.makeText(this, "All permissions granted ✓", Toast.LENGTH_SHORT).show()
            return
        }

        // Guide user through permission setup
        val firstIssue = issues.first()
        when (firstIssue.type) {
            PermissionHelper.PermissionType.OVERLAY -> {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
            PermissionHelper.PermissionType.ACCESSIBILITY -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            PermissionHelper.PermissionType.USAGE_STATS -> {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            PermissionHelper.PermissionType.NOTIFICATION_LISTENER -> {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            PermissionHelper.PermissionType.BATTERY_OPTIMIZATION -> {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    private fun startMonitorService() {
        val serviceIntent = Intent(this, AppMonitorService::class.java)
        startForegroundService(serviceIntent)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun updatePermissionStatus() {
        val missing = permissionHelper.getMissingPermissions()
        binding.btnGrantPermissions.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE

        val statusText = when {
            missing.isEmpty() -> "All systems active"
            missing.size == 1 -> "1 permission needed"
            else -> "${missing.size} permissions needed"
        }
        binding.tvPermissionStatus.text = statusText

        // Update service status indicators
        binding.tvAccessibilityStatus.text = if (FocusAccessibilityService.isRunning()) "Active ✓" else "Inactive"
        binding.tvNotifListenerStatus.text = if (FocusNotificationListenerService.instance != null) "Active ✓" else "Inactive"
    }

    private fun formatMinutes(minutes: Long): String {
        val hours = minutes / 60
        return when {
            hours == 0L -> "${minutes}m"
            hours < 24 -> "${hours}h"
            else -> "${hours / 24}d"
        }
    }
}
