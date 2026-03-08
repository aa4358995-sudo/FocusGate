package com.focusgate.ai

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// DATA MODELS
// ─────────────────────────────────────────────────────────────────────────────

data class IntentAnalysisResult(
    val isAligned: Boolean,
    val confidence: Float, // 0.0 – 1.0
    val reasoning: String,
    val nudgeSuggestion: String,
    val category: String // "work", "social", "entertainment", "utility", "unknown"
)

data class NotificationSummary(
    val totalCount: Int,
    val urgentItems: List<String>,
    val socialItems: List<String>,
    val workItems: List<String>,
    val spamItems: List<String>,
    val topSummary: String, // One-line headline
    val detailedSummary: String
)

// Gemini API request/response models
private data class GeminiRequest(
    val contents: List<GeminiContent>,
    @SerializedName("generationConfig") val generationConfig: GeminiConfig = GeminiConfig()
)

private data class GeminiContent(
    val role: String = "user",
    val parts: List<GeminiPart>
)

private data class GeminiPart(val text: String)

private data class GeminiConfig(
    val temperature: Float = 0.3f,
    @SerializedName("maxOutputTokens") val maxOutputTokens: Int = 1024,
    @SerializedName("topP") val topP: Float = 0.8f
)

private data class GeminiResponse(
    val candidates: List<GeminiCandidate>?
)

private data class GeminiCandidate(
    val content: GeminiContent?
)

// ─────────────────────────────────────────────────────────────────────────────
// AI SERVICE
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Handles all AI-powered features in FocusGate:
 * 1. Intent vs. foreground app alignment analysis
 * 2. Post-focus notification batch summarization
 * 3. Intent category classification
 */
class GeminiAIService(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiAIService"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        private const val INTENT_CACHE_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Cache to avoid repeated identical API calls
    private val intentCache = mutableMapOf<String, Pair<Long, IntentAnalysisResult>>()

    // ─── Intent Analysis ─────────────────────────────────────────────────────

    /**
     * Analyzes whether the user's foreground app matches their stated intent.
     * Uses prompt engineering to get structured JSON back from Gemini.
     */
    suspend fun analyzeIntentAlignment(
        statedIntent: String,
        foregroundApp: String,
        appLabel: String,
        appCategory: String = "unknown"
    ): IntentAnalysisResult = withContext(Dispatchers.IO) {
        // Cache check
        val cacheKey = "$statedIntent|$foregroundApp"
        val cached = intentCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < INTENT_CACHE_DURATION_MS) {
            return@withContext cached.second
        }

        val prompt = buildIntentAnalysisPrompt(statedIntent, foregroundApp, appLabel, appCategory)

        return@withContext try {
            val rawResponse = callGeminiApi(prompt)
            val result = parseIntentAnalysisResponse(rawResponse)
            intentCache[cacheKey] = System.currentTimeMillis() to result
            result
        } catch (e: Exception) {
            Log.e(TAG, "Intent analysis failed: ${e.message}")
            // Fail open — assume aligned to avoid false positives
            IntentAnalysisResult(
                isAligned = true,
                confidence = 0.5f,
                reasoning = "Analysis unavailable",
                nudgeSuggestion = "",
                category = "unknown"
            )
        }
    }

    private fun buildIntentAnalysisPrompt(
        intent: String,
        packageName: String,
        appLabel: String,
        appCategory: String
    ): String = """
You are FocusGate's intent analyzer. Your job is to determine if a user's phone activity matches their stated intent.

STATED INTENT: "$intent"
CURRENT APP: $appLabel (package: $packageName, category: $appCategory)

Analyze whether this app usage is reasonably aligned with the stated intent.

Rules:
- Be LENIENT with productive apps (browsers used for research, notes apps, etc.)
- Be STRICT with pure entertainment/social apps that clearly deviate (TikTok, Instagram Reels, games)
- Context matters: "check email" could justify opening a browser
- System apps and utilities are almost always acceptable
- If the intent is vague (e.g., "just browsing"), default to aligned=true

Respond ONLY with valid JSON (no markdown, no backticks):
{
  "isAligned": <boolean>,
  "confidence": <float 0.0-1.0>,
  "reasoning": "<one sentence explanation>",
  "nudgeSuggestion": "<gentle, non-judgmental reminder if not aligned, empty string if aligned>",
  "category": "<one of: work, study, social, entertainment, utility, communication, health, finance, unknown>"
}
""".trimIndent()

    private fun parseIntentAnalysisResponse(raw: String): IntentAnalysisResult {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val map = gson.fromJson(cleaned, Map::class.java)
            IntentAnalysisResult(
                isAligned = (map["isAligned"] as? Boolean) ?: true,
                confidence = ((map["confidence"] as? Double)?.toFloat()) ?: 0.5f,
                reasoning = (map["reasoning"] as? String) ?: "",
                nudgeSuggestion = (map["nudgeSuggestion"] as? String) ?: "",
                category = (map["category"] as? String) ?: "unknown"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse intent response: $raw")
            IntentAnalysisResult(true, 0.5f, "Parse error", "", "unknown")
        }
    }

    // ─── Notification Summarization ──────────────────────────────────────────

    /**
     * Takes a batch of intercepted notifications and returns an AI-generated digest.
     */
    suspend fun summarizeNotifications(
        notifications: List<com.focusgate.data.CapturedNotification>,
        sessionDurationMinutes: Int
    ): NotificationSummary = withContext(Dispatchers.IO) {
        if (notifications.isEmpty()) {
            return@withContext NotificationSummary(
                totalCount = 0,
                urgentItems = emptyList(),
                socialItems = emptyList(),
                workItems = emptyList(),
                spamItems = emptyList(),
                topSummary = "Nothing to report — it was quiet while you focused.",
                detailedSummary = "No notifications were received during your ${sessionDurationMinutes}-minute focus session."
            )
        }

        val prompt = buildNotificationSummaryPrompt(notifications, sessionDurationMinutes)

        return@withContext try {
            val rawResponse = callGeminiApi(prompt)
            parseNotificationSummaryResponse(rawResponse, notifications.size)
        } catch (e: Exception) {
            Log.e(TAG, "Notification summarization failed: ${e.message}")
            NotificationSummary(
                totalCount = notifications.size,
                urgentItems = emptyList(),
                socialItems = emptyList(),
                workItems = emptyList(),
                spamItems = emptyList(),
                topSummary = "${notifications.size} notifications arrived while you focused.",
                detailedSummary = "Unable to summarize notifications at this time."
            )
        }
    }

    private fun buildNotificationSummaryPrompt(
        notifications: List<com.focusgate.data.CapturedNotification>,
        sessionDurationMinutes: Int
    ): String {
        val notifList = notifications.take(50).joinToString("\n") { notif ->
            "- [${notif.appName}] ${notif.title}: ${notif.text.take(100)}"
        }

        return """
You are FocusGate's notification analyst. The user just completed a ${sessionDurationMinutes}-minute focus session and you must summarize what they missed.

INTERCEPTED NOTIFICATIONS (${notifications.size} total):
$notifList

Categorize and summarize these notifications. Be concise, calm, and reassuring. The user should feel like they didn't miss anything critical.

Respond ONLY with valid JSON (no markdown, no backticks):
{
  "topSummary": "<one headline sentence, e.g. '8 messages, mostly social — nothing urgent'>",
  "detailedSummary": "<2-3 sentence overview of what happened>",
  "urgentItems": ["<only truly time-sensitive items, max 3>"],
  "workItems": ["<work/productivity related items, max 5>"],
  "socialItems": ["<social/messaging items, max 5>"],
  "spamItems": ["<promotional/spam items summary>"]
}
""".trimIndent()
    }

    private fun parseNotificationSummaryResponse(raw: String, totalCount: Int): NotificationSummary {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()

            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(cleaned, Map::class.java)

            fun getStringList(key: String): List<String> {
                val list = map[key] as? List<*> ?: return emptyList()
                return list.filterIsInstance<String>()
            }

            NotificationSummary(
                totalCount = totalCount,
                urgentItems = getStringList("urgentItems"),
                socialItems = getStringList("socialItems"),
                workItems = getStringList("workItems"),
                spamItems = getStringList("spamItems"),
                topSummary = (map["topSummary"] as? String) ?: "$totalCount notifications",
                detailedSummary = (map["detailedSummary"] as? String) ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notification response: $raw")
            NotificationSummary(
                totalCount = totalCount,
                urgentItems = emptyList(),
                socialItems = emptyList(),
                workItems = emptyList(),
                spamItems = emptyList(),
                topSummary = "$totalCount notifications while you focused.",
                detailedSummary = "Summary unavailable."
            )
        }
    }

    // ─── Intent Classification ────────────────────────────────────────────────

    /**
     * Classifies a raw intent string into a structured category.
     * Used to validate that the intent is specific enough.
     */
    suspend fun classifyIntent(rawIntent: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (rawIntent.length < 5) return@withContext Pair(false, "Please be more specific about your intent.")

        val prompt = """
Evaluate if this phone usage intent is specific enough to be meaningful: "$rawIntent"

A good intent is specific (e.g., "check work email", "look up a recipe", "call mom").
A bad intent is vague (e.g., "just browsing", "idk", "stuff").

Respond ONLY with JSON:
{"isSpecific": <boolean>, "feedback": "<brief encouraging feedback if not specific, empty if good>"}
""".trimIndent()

        return@withContext try {
            val raw = callGeminiApi(prompt)
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val map = gson.fromJson(cleaned, Map::class.java)
            val isSpecific = (map["isSpecific"] as? Boolean) ?: true
            val feedback = (map["feedback"] as? String) ?: ""
            Pair(isSpecific, feedback)
        } catch (e: Exception) {
            Pair(true, "") // Fail open
        }
    }

    // ─── Core API Call ───────────────────────────────────────────────────────

    private fun callGeminiApi(prompt: String): String {
        val url = "$BASE_URL?key=$apiKey"
        val requestBody = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(prompt))))
        )
        val json = gson.toJson(requestBody)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("API error ${response.code}: ${response.body?.string()}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
        return geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw Exception("No content in response")
    }
}
