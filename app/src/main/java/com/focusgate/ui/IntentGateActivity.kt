package com.focusgate.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusgate.FocusGateApplication
import com.focusgate.R
import com.focusgate.ai.GeminiAIService
import com.focusgate.data.FocusRepository
import com.focusgate.data.FocusSession
import com.focusgate.data.PreferencesManager
import com.focusgate.databinding.ActivityIntentGateBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * IntentGateActivity — "The Oasis"
 *
 * Full-screen, distraction-free overlay that intercepts every phone unlock.
 * The user must state their intent before proceeding.
 *
 * Design philosophy: Calm, meditative, non-aggressive.
 * The typography, breathing animation, and sage-green palette
 * are deliberately soothing — this is a sanctuary, not a barrier.
 */
class IntentGateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIntentGateBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: FocusRepository

    private var aiService: GeminiAIService? = null
    private var currentSessionId = UUID.randomUUID().toString()
    private var isProcessing = false

    // Quick-select intent suggestions
    private val suggestions = listOf(
        "Check messages", "Send an email", "Look something up",
        "Call someone", "Check the time", "Take a photo",
        "Set an alarm", "Play music", "Check the news"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIntentGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = FocusGateApplication.instance.preferencesManager
        repository = FocusRepository(this)

        // Prevent back button from bypassing the gate
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Soft dismiss — show gentle message instead of hard block
                binding.tvSubtitle.text = getString(R.string.gate_back_blocked_message)
                shakeInput()
            }
        })

        setupUI()
        initAI()
        startBreathingAnimation()
        showKeyboard()
    }

    private fun setupUI() {
        // Quick suggestion chips
        setupSuggestionChips()

        // Intent input
        binding.etIntent.apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val hasText = s?.trim()?.isNotEmpty() == true
                    binding.btnProceed.isEnabled = hasText
                    binding.btnProceed.alpha = if (hasText) 1f else 0.4f
                    if (hasText) binding.tvCharHint.visibility = View.GONE
                }
                override fun afterTextChanged(s: Editable?) {}
            })

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    handleProceed()
                    true
                } else false
            }
        }

        // Proceed button
        binding.btnProceed.setOnClickListener { handleProceed() }

        // Work Mode shortcut
        binding.btnStartWorkMode.setOnClickListener {
            val intent = binding.etIntent.text?.trim()?.toString() ?: ""
            val workIntent = Intent(this, WorkModeActivity::class.java).apply {
                if (intent.isNotBlank()) putExtra("INITIAL_INTENT", intent)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(workIntent)
            finish()
        }

        // Emergency / exempt bypass
        binding.btnEmergency.setOnClickListener {
            lifecycleScope.launch {
                prefs.setCurrentIntent("EMERGENCY_BYPASS", currentSessionId)
                finishWithProceed("Emergency bypass")
            }
        }

        // Show time
        updateTimeDisplay()
    }

    private fun setupSuggestionChips() {
        val chipContainer = binding.chipGroupSuggestions
        chipContainer.removeAllViews()

        suggestions.shuffled().take(5).forEach { suggestion ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = suggestion
                setChipBackgroundColorResource(R.color.chip_background)
                setTextColor(getColor(R.color.sage_green_dark))
                chipStrokeWidth = 1f
                setChipStrokeColorResource(R.color.sage_green_light)
                setOnClickListener {
                    binding.etIntent.setText(suggestion)
                    binding.etIntent.setSelection(suggestion.length)
                }
            }
            chipContainer.addView(chip)
        }
    }

    private fun initAI() {
        lifecycleScope.launch {
            val apiKey = prefs.getApiKeyOnce("gemini")
            if (apiKey.isNotBlank()) {
                aiService = GeminiAIService(apiKey)
            }
        }
    }

    private fun handleProceed() {
        if (isProcessing) return
        val intentText = binding.etIntent.text?.trim()?.toString() ?: return
        if (intentText.length < 3) {
            shakeInput()
            binding.tvCharHint.visibility = View.VISIBLE
            return
        }
        isProcessing = true
        processIntentWithAI(intentText)
    }

    private fun processIntentWithAI(intentText: String) {
        hideKeyboard()

        binding.btnProceed.isEnabled = false
        binding.loadingGroup.visibility = View.VISIBLE
        binding.tvLoadingMessage.text = getString(R.string.gate_ai_thinking)

        lifecycleScope.launch {
            try {
                val ai = aiService
                if (ai != null) {
                    val (isSpecific, feedback) = ai.classifyIntent(intentText)
                    if (!isSpecific && feedback.isNotBlank()) {
                        // Give user feedback but don't hard-block
                        binding.loadingGroup.visibility = View.GONE
                        binding.tvAiFeedback.apply {
                            text = feedback
                            visibility = View.VISIBLE
                        }
                        // Allow 2 second read time, then allow proceed anyway
                        delay(2000)
                        binding.tvAiFeedback.visibility = View.GONE
                    }
                }
                saveAndProceed(intentText)
            } catch (e: Exception) {
                // AI failed — don't block the user
                saveAndProceed(intentText)
            }
        }
    }

    private suspend fun saveAndProceed(intentText: String) {
        // Save intent to preferences
        prefs.setCurrentIntent(intentText, currentSessionId)
        prefs.incrementGatesPassed()

        // Create session record
        repository.saveSession(
            FocusSession(
                id = currentSessionId,
                intent = intentText,
                startTime = System.currentTimeMillis(),
                isWorkMode = false
            )
        )

        finishWithProceed(intentText)
    }

    private fun finishWithProceed(intentText: String) {
        binding.loadingGroup.visibility = View.GONE
        // Play a soft exit animation
        binding.root.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction { finish() }
            .start()
    }

    private fun startBreathingAnimation() {
        binding.ivBreathingOrb.apply {
            animate()
                .scaleX(1.15f).scaleY(1.15f)
                .setDuration(3000)
                .withEndAction {
                    animate()
                        .scaleX(0.95f).scaleY(0.95f)
                        .setDuration(3000)
                        .withEndAction { startBreathingAnimation() }
                        .start()
                }
                .start()
        }
    }

    private fun updateTimeDisplay() {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
        binding.tvGreeting.text = greeting

        // Time of day
        val timeStr = String.format(
            "%d:%02d",
            if (hour > 12) hour - 12 else if (hour == 0) 12 else hour,
            now.get(java.util.Calendar.MINUTE)
        )
        binding.tvTime.text = timeStr
    }

    private fun shakeInput() {
        val anim = AnimationUtils.loadAnimation(this, R.anim.shake)
        binding.etIntent.startAnimation(anim)
    }

    private fun showKeyboard() {
        binding.etIntent.postDelayed({
            binding.etIntent.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(binding.etIntent, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.etIntent.windowToken, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Immersive mode — hide system bars for full immersion
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }
}
