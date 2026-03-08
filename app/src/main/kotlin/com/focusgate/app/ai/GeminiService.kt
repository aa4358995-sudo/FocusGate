package com.focusgate.app.ai

import android.content.Context
import com.focusgate.app.BuildConfig
import com.focusgate.app.data.InterceptedNotification
import com.focusgate.app.state.FocusStateManager
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * GeminiService
 * ─────────────
 * Handles all AI operations using Google's Gemini 1.5 Flash model.
 *
 * Operations:
 *  1. [checkIntentMatch]           – Determines if active app matches stated intent
 *  2. [summarizeNotifications]     – Produces a human-readable notification digest
 *  3. [parseIntentCategory]        – Classifies intent text into focus categories
 *
 * Prompts are carefully engineered to:
 *  • Return only structured JSON
 *  • Be context-aware and avoid false positives
 *  • Respect user privacy (no PII sent unnecessarily)
 */
class GeminiService(private val context: Context) {

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/"
        private const val MODEL    = "models/gemini-1.5-flash"
    }

    private val stateManager = (context.applicationContext as? com.focusgate.app.FocusGateApp)
        ?.focusStateManager

    // ─── Retrofit Setup ──────────────────────────────────────────────────────

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(GeminiApi::class.java)

    // ─── API Key Resolution ───────────────────────────────────────────────────

    private suspend fun getApiKey(): String {
        // Prefer user-saved key from DataStore, fall back to BuildConfig
        val userKey = stateManager?.geminiApiKey?.first()
        return if (!userKey.isNullOrBlank()) userKey
        else BuildConfig.GEMINI_API_KEY
    }

    // ─── 1. Intent Match Check ────────────────────────────────────────────────

    /**
     * Determines if the user's active app is aligned with their stated intent.
     *
     * Returns true  → App matches intent (no nudge)
     * Returns false → Mismatch detected (escalate nudge)
     *
     * The prompt is designed to be LENIENT to avoid frustrating false positives.
     * E.g., "Study coding" + "StackOverflow" = MATCH
     *       "Study coding" + "TikTok"        = MISMATCH
     *       "Check email"  + "Gmail"          = MATCH
     *       "Check email"  + "Instagram"      = MISMATCH
     */
    suspend fun checkIntentMatch(
        statedIntent: String,
        activeAppName: String,
        activePackage: String
    ): Boolean = withContext(Dispatchers.IO) {

        val prompt = """
            You are a digital wellbeing assistant helping a user stay focused.
            
            The user stated their intent for using their phone: "$statedIntent"
            They are currently using: "$activeAppName" (package: $activePackage)
            
            Determine if using "$activeAppName" is reasonably aligned with their stated intent.
            
            Guidelines:
            - Be LENIENT. Give benefit of the doubt for ambiguous cases.
            - Productivity apps (notes, calculator, browser) generally match most intents.
            - Social media, games, and entertainment apps rarely match focused work intents.
            - If the intent mentions "research" or "study", browsers and educational apps match.
            - System apps (settings, files) generally match any intent.
            
            Respond ONLY with valid JSON, no markdown, no explanation:
            {"match": true|false, "reason": "brief one-sentence explanation"}
        """.trimIndent()

        return@withContext try {
            val response = api.generateContent(
                apiKey = getApiKey(),
                body   = buildRequest(prompt)
            )
            val text = response.candidates?.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text
                ?: return@withContext true // Fail safe: allow on error

            val result = parseJson<IntentMatchResult>(text)
            result?.match ?: true
        } catch (e: Exception) {
            android.util.Log.e("GeminiService", "Intent match check failed", e)
            true // Fail safe: don't nudge if AI is unreachable
        }
    }

    // ─── 2. Notification Summarization ───────────────────────────────────────

    /**
     * Summarizes a list of intercepted notifications into a human-readable digest.
     * Categorizes by urgency and groups by app.
     */
    suspend fun summarizeNotifications(
        notifications: List<InterceptedNotification>
    ): String = withContext(Dispatchers.IO) {

        val notifData = notifications.joinToString("\n") { n ->
            "• [${n.appName}] ${n.title}: ${n.text.take(100)}"
        }

        val prompt = """
            You are a mindful digital assistant summarizing notifications a user missed during a focus session.
            
            Notifications received (${notifications.size} total):
            $notifData
            
            Create a calm, concise Post-Focus Digest. Structure your response as:
            
            1. One-sentence overview (e.g. "You received 8 notifications, mostly social updates.")
            2. By-app summary: list each app with a brief count and the most important message
            3. Urgency assessment: flag any messages that seem time-sensitive
            4. Suggested action: one gentle recommendation (e.g. "Check your work email when ready.")
            
            Keep the tone calm, non-judgmental, and concise. Do not reproduce full message text.
            Respond in plain text, no JSON, no markdown headers.
        """.trimIndent()

        return@withContext try {
            val response = api.generateContent(
                apiKey = getApiKey(),
                body   = buildRequest(prompt)
            )
            response.candidates?.firstOrNull()
                ?.content?.parts?.firstOrNull()?.text
                ?: buildFallbackSummary(notifications)
        } catch (e: Exception) {
            android.util.Log.e("GeminiService", "Notification summary failed", e)
            buildFallbackSummary(notifications)
        }
    }

    // ─── 3. Intent Category Classification ───────────────────────────────────

    /**
     * Classifies the user's stated intent into a category for
     * analytics and smart exception handling.
     */
    suspend fun parseIntentCategory(intentText: String): IntentCategory =
        withContext(Dispatchers.IO) {
            val prompt = """
                Classify this phone usage intent into exactly one category.
                Intent: "$intentText"
                
                Categories: WORK, STUDY, COMMUNICATION, ENTERTAINMENT, HEALTH, SHOPPING, NAVIGATION, OTHER
                
                Respond ONLY with JSON: {"category": "CATEGORY_NAME", "confidence": 0.0-1.0}
            """.trimIndent()

            return@withContext try {
                val response = api.generateContent(
                    apiKey = getApiKey(),
                    body   = buildRequest(prompt)
                )
                val text = response.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()?.text ?: ""
                val result = parseJson<CategoryResult>(text)
                IntentCategory.valueOf(result?.category ?: "OTHER")
            } catch (_: Exception) {
                IntentCategory.OTHER
            }
        }

    // ─── Request Builder ─────────────────────────────────────────────────────

    private fun buildRequest(prompt: String) = GeminiRequest(
        contents = listOf(
            Content(parts = listOf(Part(text = prompt)))
        ),
        generationConfig = GenerationConfig(
            temperature     = 0.1f,   // Low temperature for consistent, factual responses
            maxOutputTokens = 512,
            topP            = 0.8f
        ),
        safetySettings = listOf(
            SafetySetting("HARM_CATEGORY_HARASSMENT",        "BLOCK_NONE"),
            SafetySetting("HARM_CATEGORY_HATE_SPEECH",       "BLOCK_NONE"),
            SafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_NONE"),
            SafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_NONE")
        )
    )

    private fun buildFallbackSummary(notifications: List<InterceptedNotification>): String {
        val grouped = notifications.groupBy { it.appName }
        return buildString {
            appendLine("You received ${notifications.size} notification(s) during your focus session:")
            appendLine()
            grouped.forEach { (app, notifs) ->
                appendLine("• $app: ${notifs.size} notification(s)")
            }
            appendLine()
            appendLine("Review them when you're ready.")
        }
    }

    // ─── JSON Parsing ─────────────────────────────────────────────────────────

    private inline fun <reified T> parseJson(raw: String): T? {
        return try {
            // Strip markdown fences if present
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            com.google.gson.Gson().fromJson(cleaned, T::class.java)
        } catch (_: Exception) { null }
    }
}

// ─── Retrofit API Interface ───────────────────────────────────────────────────

interface GeminiApi {
    @Headers("Content-Type: application/json")
    @POST("$MODEL:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body body: GeminiRequest
    ): GeminiResponse
}

// ─── Request/Response DTOs ────────────────────────────────────────────────────

data class GeminiRequest(
    val contents: List<Content>,
    @SerializedName("generationConfig")
    val generationConfig: GenerationConfig,
    @SerializedName("safetySettings")
    val safetySettings: List<SafetySetting>
)

data class Content(val parts: List<Part>, val role: String = "user")
data class Part(val text: String)
data class GenerationConfig(
    val temperature: Float,
    @SerializedName("maxOutputTokens") val maxOutputTokens: Int,
    val topP: Float
)
data class SafetySetting(val category: String, val threshold: String)

data class GeminiResponse(
    val candidates: List<Candidate>?
)

data class Candidate(val content: Content?)

// ─── Internal result types ─────────────────────────────────────────────────────
private data class IntentMatchResult(val match: Boolean, val reason: String?)
private data class CategoryResult(val category: String, val confidence: Float)

// ─── Public enums ─────────────────────────────────────────────────────────────
enum class IntentCategory {
    WORK, STUDY, COMMUNICATION, ENTERTAINMENT, HEALTH, SHOPPING, NAVIGATION, OTHER
}
