package com.focusgate.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.focusgate.FocusGateApplication
import com.focusgate.R
import com.focusgate.data.FocusRepository
import com.focusgate.data.FocusSession
import com.focusgate.data.PreferencesManager
import com.focusgate.databinding.ActivityWorkModeBinding
import com.focusgate.services.FocusNotificationListenerService
import com.focusgate.utils.EmergencyPhraseGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * WorkModeActivity — The Sanctuary
 *
 * A locked, minimal launcher that replaces the standard OS environment
 * during focus sessions. Only whitelisted apps are accessible.
 *
 * Cannot be exited until:
 * a) The countdown timer reaches zero, OR
 * b) The Emergency Exit protocol is completed (50-word complex paragraph)
 */
class WorkModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWorkModeBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: FocusRepository

    private var countDownTimer: CountDownTimer? = null
    private var sessionId = UUID.randomUUID().toString()
    private var emergencyPhrase = ""
    private var totalDurationMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = FocusGateApplication.instance.preferencesManager
        repository = FocusRepository(this)

        // Block the back button completely during Work Mode
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showBlockedFeedback("Back button is disabled during Work Mode.")
            }
        })

        checkIfAlreadyInWorkMode()
    }

    private fun checkIfAlreadyInWorkMode() {
        lifecycleScope.launch {
            val isActive = prefs.isWorkModeActive.first()
            if (isActive) {
                // Resume existing work mode session
                val endTime = prefs.workModeEndTime.first()
                val whitelist = prefs.workModeWhitelistedApps.first()
                emergencyPhrase = prefs.emergencyExitPhrase.first()
                setupWorkModeUI(endTime, whitelist)
            } else {
                // New work mode — go to setup (shouldn't be here without setup, redirect)
                val initialIntent = intent.getStringExtra("INITIAL_INTENT")
                startActivity(Intent(this@WorkModeActivity, MainActivity::class.java).apply {
                    putExtra("START_WORK_MODE_SETUP", true)
                    initialIntent?.let { putExtra("INITIAL_INTENT", it) }
                })
                finish()
            }
        }
    }

    fun activateWorkMode(durationMinutes: Int, whitelist: Set<String>, userIntent: String) {
        totalDurationMs = durationMinutes * 60 * 1000L
        val endTime = System.currentTimeMillis() + totalDurationMs
        emergencyPhrase = EmergencyPhraseGenerator.generate()

        lifecycleScope.launch {
            prefs.startWorkMode(endTime, whitelist, emergencyPhrase)

            // Start notification capture
            FocusNotificationListenerService.startCapturing(sessionId)

            // Save session
            repository.saveSession(
                FocusSession(
                    id = sessionId,
                    intent = userIntent,
                    startTime = System.currentTimeMillis(),
                    isWorkMode = true,
                    whitelistedApps = whitelist.joinToString(","),
                    durationMinutes = durationMinutes
                )
            )

            setupWorkModeUI(endTime, whitelist)
        }
    }

    private fun setupWorkModeUI(endTimeMillis: Long, whitelist: Set<String>) {
        val remaining = endTimeMillis - System.currentTimeMillis()
        if (remaining <= 0) {
            handleSessionComplete()
            return
        }

        binding.apply {
            // Setup whitelisted app grid
            setupAppGrid(whitelist)

            // Start countdown timer
            startCountdownTimer(remaining)

            // Emergency exit button
            btnEmergencyExit.setOnClickListener { showEmergencyExitDialog() }

            // Time display
            tvSessionLabel.text = getString(R.string.work_mode_active_label)
        }

        // Immersive mode
        enterImmersiveMode()
    }

    private fun setupAppGrid(whitelist: Set<String>) {
        val apps = whitelist.mapNotNull { packageName ->
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(appInfo)
                AppGridItem(packageName, label, icon)
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.label }

        val adapter = WorkModeAppAdapter(apps) { packageName ->
            launchWhitelistedApp(packageName)
        }

        binding.rvWhitelistedApps.apply {
            layoutManager = GridLayoutManager(this@WorkModeActivity, 3)
            this.adapter = adapter
        }
    }

    private fun launchWhitelistedApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Unable to open app", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open app", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCountdownTimer(remainingMs: Long) {
        countDownTimer?.cancel()
        totalDurationMs = if (totalDurationMs == 0L) remainingMs else totalDurationMs

        countDownTimer = object : CountDownTimer(remainingMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val hours = millisUntilFinished / 3_600_000
                val minutes = (millisUntilFinished % 3_600_000) / 60_000
                val seconds = (millisUntilFinished % 60_000) / 1000

                val timeString = if (hours > 0) {
                    String.format("%d:%02d:%02d", hours, minutes, seconds)
                } else {
                    String.format("%02d:%02d", minutes, seconds)
                }

                binding.tvCountdown.text = timeString

                // Progress ring
                val progress = ((totalDurationMs - millisUntilFinished).toFloat() / totalDurationMs * 100).toInt()
                binding.progressTimer.progress = progress
            }

            override fun onFinish() {
                binding.tvCountdown.text = "00:00"
                binding.progressTimer.progress = 100
                handleSessionComplete()
            }
        }.start()
    }

    private fun handleSessionComplete() {
        countDownTimer?.cancel()

        lifecycleScope.launch {
            val capturedSessionId = FocusNotificationListenerService.stopCapturing()
            prefs.endWorkMode()

            // Calculate actual duration
            val startTime = prefs.preferences.first()[PreferencesManager.KEY_WORK_MODE_START_TIME] ?: 0L
            val durationMinutes = ((System.currentTimeMillis() - startTime) / 60_000).toInt()
            prefs.addFocusMinutes(durationMinutes.toLong())

            // Mark session as complete
            val session = repository.getSession(sessionId)
            session?.let {
                repository.updateSession(
                    it.copy(
                        endTime = System.currentTimeMillis(),
                        completed = true,
                        durationMinutes = durationMinutes
                    )
                )
            }

            // Launch digest
            val digestIntent = Intent(this@WorkModeActivity, DigestActivity::class.java).apply {
                putExtra("SESSION_ID", capturedSessionId.ifBlank { sessionId })
                putExtra("DURATION_MINUTES", durationMinutes)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(digestIntent)
            finish()
        }
    }

    // ─── Emergency Exit ───────────────────────────────────────────────────────

    private fun showEmergencyExitDialog() {
        if (emergencyPhrase.isBlank()) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_emergency_exit, null)
        val tvPhrase = dialogView.findViewById<android.widget.TextView>(R.id.tv_emergency_phrase)
        val etInput = dialogView.findViewById<EditText>(R.id.et_emergency_input)

        tvPhrase.text = emergencyPhrase

        AlertDialog.Builder(this, R.style.EmergencyExitDialog)
            .setView(dialogView)
            .setTitle("Emergency Exit")
            .setMessage("Type the following paragraph EXACTLY to exit Work Mode:")
            .setPositiveButton("Confirm Exit") { _, _ ->
                val typed = etInput.text?.toString()?.trim() ?: ""
                verifyEmergencyPhrase(typed)
            }
            .setNegativeButton("Stay Focused") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun verifyEmergencyPhrase(typed: String) {
        // Normalize whitespace for comparison
        val normalize = { s: String -> s.lowercase().replace("\\s+".toRegex(), " ").trim() }
        val expected = normalize(emergencyPhrase)
        val actual = normalize(typed)

        if (actual == expected) {
            // Valid emergency exit
            lifecycleScope.launch {
                FocusNotificationListenerService.stopCapturing()
                prefs.endWorkMode()
                Toast.makeText(this@WorkModeActivity, "Work Mode ended.", Toast.LENGTH_SHORT).show()

                val homeIntent = Intent(this@WorkModeActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(homeIntent)
                finish()
            }
        } else {
            showBlockedFeedback("Paragraph doesn't match. Try again.")
        }
    }

    private fun showBlockedFeedback(message: String) {
        binding.tvBlockedMessage.apply {
            text = message
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(200).withEndAction {
                postDelayed({
                    animate().alpha(0f).setDuration(500).withEndAction {
                        visibility = View.GONE
                    }.start()
                }, 2500)
            }.start()
        }
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }

    override fun onPause() {
        super.onPause()
        // When user navigates away to a whitelisted app, that's expected.
        // The AccessibilityService handles blocking non-whitelisted apps.
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
        // Re-check if work mode is still active
        lifecycleScope.launch {
            val isActive = prefs.isWorkModeActive.first()
            if (!isActive) {
                finish()
            }
        }
    }
}

// ─── Data classes ─────────────────────────────────────────────────────────────

data class AppGridItem(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable
)
