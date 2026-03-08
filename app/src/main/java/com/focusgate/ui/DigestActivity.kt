package com.focusgate.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.focusgate.FocusGateApplication
import com.focusgate.R
import com.focusgate.ai.GeminiAIService
import com.focusgate.ai.NotificationSummary
import com.focusgate.data.FocusRepository
import com.focusgate.databinding.ActivityDigestBinding
import kotlinx.coroutines.launch

/**
 * DigestActivity — The Debrief
 *
 * Shown after every Work Mode session completes.
 * Presents an AI-generated summary of what the user missed
 * while they were focused — calm, clear, non-anxious.
 */
class DigestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDigestBinding
    private lateinit var repository: FocusRepository
    private var aiService: GeminiAIService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDigestBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = FocusRepository(this)

        val sessionId = intent.getStringExtra("SESSION_ID") ?: ""
        val durationMinutes = intent.getIntExtra("DURATION_MINUTES", 0)

        setupUI(durationMinutes)
        loadAndSummarize(sessionId, durationMinutes)
    }

    private fun setupUI(durationMinutes: Int) {
        binding.apply {
            tvSessionDuration.text = formatDuration(durationMinutes)
            btnDone.setOnClickListener {
                startActivity(Intent(this@DigestActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                finish()
            }
            btnStartAnother.setOnClickListener {
                startActivity(Intent(this@DigestActivity, MainActivity::class.java).apply {
                    putExtra("START_WORK_MODE_SETUP", true)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                })
                finish()
            }
        }
    }

    private fun loadAndSummarize(sessionId: String, durationMinutes: Int) {
        binding.loadingGroup.visibility = View.VISIBLE
        binding.contentGroup.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val notifications = repository.getNotificationsForSession(sessionId)
                val prefs = FocusGateApplication.instance.preferencesManager
                val apiKey = prefs.getApiKeyOnce("gemini")

                val summary: NotificationSummary = if (apiKey.isNotBlank()) {
                    aiService = GeminiAIService(apiKey)
                    aiService!!.summarizeNotifications(notifications, durationMinutes)
                } else {
                    // Fallback without AI
                    buildManualSummary(notifications, durationMinutes)
                }

                displaySummary(summary, durationMinutes)
            } catch (e: Exception) {
                showError(e.message ?: "Something went wrong")
            }
        }
    }

    private fun displaySummary(summary: NotificationSummary, durationMinutes: Int) {
        binding.apply {
            loadingGroup.visibility = View.GONE
            contentGroup.visibility = View.VISIBLE

            tvHeadline.text = summary.topSummary
            tvDetailedSummary.text = summary.detailedSummary

            // Urgent items
            if (summary.urgentItems.isNotEmpty()) {
                cardUrgent.visibility = View.VISIBLE
                tvUrgentItems.text = summary.urgentItems.joinToString("\n• ", prefix = "• ")
            } else {
                cardUrgent.visibility = View.GONE
            }

            // Work items
            if (summary.workItems.isNotEmpty()) {
                cardWork.visibility = View.VISIBLE
                tvWorkItems.text = summary.workItems.joinToString("\n• ", prefix = "• ")
            } else {
                cardWork.visibility = View.GONE
            }

            // Social items
            if (summary.socialItems.isNotEmpty()) {
                cardSocial.visibility = View.VISIBLE
                tvSocialItems.text = summary.socialItems.joinToString("\n• ", prefix = "• ")
            } else {
                cardSocial.visibility = View.GONE
            }

            // Stats row
            tvNotificationCount.text = "${summary.totalCount} intercepted"
            tvFocusTime.text = formatDuration(durationMinutes)

            // Congratulations message
            val congrats = listOf(
                "Well done. You chose depth over distraction.",
                "That's ${durationMinutes} minutes of your best thinking — protected.",
                "A focused mind is a powerful one. Nicely done.",
                "Your attention is your most valuable asset. You just invested it wisely."
            ).random()
            tvCongratsMessage.text = congrats

            // Animate content in
            contentGroup.alpha = 0f
            contentGroup.animate().alpha(1f).setDuration(600).start()
        }
    }

    private fun buildManualSummary(
        notifications: List<com.focusgate.data.CapturedNotification>,
        durationMinutes: Int
    ): NotificationSummary {
        val byApp = notifications.groupBy { it.appName }
        val summary = byApp.entries.take(5).joinToString(", ") {
            "${it.value.size} from ${it.key}"
        }

        return NotificationSummary(
            totalCount = notifications.size,
            urgentItems = emptyList(),
            socialItems = emptyList(),
            workItems = emptyList(),
            spamItems = emptyList(),
            topSummary = if (notifications.isEmpty())
                "No notifications — it was quiet while you focused."
            else
                "${notifications.size} notifications while you focused.",
            detailedSummary = if (summary.isNotBlank()) summary else "Nothing urgent."
        )
    }

    private fun showError(message: String) {
        binding.loadingGroup.visibility = View.GONE
        binding.contentGroup.visibility = View.VISIBLE
        binding.tvHeadline.text = "Session complete!"
        binding.tvDetailedSummary.text = "Unable to summarize notifications. Check your API key in settings."
    }

    private fun formatDuration(minutes: Int): String {
        return when {
            minutes < 60 -> "${minutes}m"
            minutes % 60 == 0 -> "${minutes / 60}h"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
}
