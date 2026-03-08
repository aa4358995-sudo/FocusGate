package com.focusgate.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "focus_gate_prefs")

/**
 * Centralized preferences manager using DataStore.
 * All persistent app state lives here.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        // Gate settings
        val KEY_GATE_ENABLED = booleanPreferencesKey("gate_enabled")
        val KEY_CURRENT_INTENT = stringPreferencesKey("current_intent")
        val KEY_INTENT_TIMESTAMP = longPreferencesKey("intent_timestamp")
        val KEY_INTENT_SESSION_ID = stringPreferencesKey("intent_session_id")

        // Work Mode
        val KEY_WORK_MODE_ACTIVE = booleanPreferencesKey("work_mode_active")
        val KEY_WORK_MODE_END_TIME = longPreferencesKey("work_mode_end_time")
        val KEY_WORK_MODE_START_TIME = longPreferencesKey("work_mode_start_time")
        val KEY_WORK_MODE_WHITELISTED_APPS = stringPreferencesKey("work_mode_whitelist")
        val KEY_EMERGENCY_EXIT_PHRASE = stringPreferencesKey("emergency_exit_phrase")

        // Nudge System
        val KEY_NUDGE_LEVEL = intPreferencesKey("nudge_level")
        val KEY_NUDGE_COUNT_TODAY = intPreferencesKey("nudge_count_today")
        val KEY_LAST_NUDGE_DATE = stringPreferencesKey("last_nudge_date")

        // AI / API
        val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider")  // "gemini" or "openai"
        val KEY_OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")

        // Onboarding
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_PERMISSIONS_GRANTED = booleanPreferencesKey("permissions_granted")

        // Exempt apps (always allowed through gate)
        val KEY_EXEMPT_APPS = stringPreferencesKey("exempt_apps")

        // Statistics
        val KEY_TOTAL_GATES_PASSED = intPreferencesKey("total_gates_passed")
        val KEY_TOTAL_FOCUS_MINUTES = longPreferencesKey("total_focus_minutes")
        val KEY_STREAK_DAYS = intPreferencesKey("streak_days")

        // Intent validity window (minutes before re-prompting)
        val KEY_INTENT_VALIDITY_MINUTES = intPreferencesKey("intent_validity_minutes")

        // Default exempt apps
        val DEFAULT_EXEMPT_APPS = setOf(
            "com.android.phone",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.emergency",
            "com.android.camera",
            "com.android.camera2",
            "com.focusgate" // Never block ourselves
        )
    }

    val preferences: Flow<Preferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }

    // ─── Gate ────────────────────────────────────────────────────────────────

    val isGateEnabled: Flow<Boolean> = preferences.map { it[KEY_GATE_ENABLED] ?: true }

    val currentIntent: Flow<String> = preferences.map { it[KEY_CURRENT_INTENT] ?: "" }

    val intentTimestamp: Flow<Long> = preferences.map { it[KEY_INTENT_TIMESTAMP] ?: 0L }

    val intentValidityMinutes: Flow<Int> = preferences.map { it[KEY_INTENT_VALIDITY_MINUTES] ?: 30 }

    // ─── Work Mode ───────────────────────────────────────────────────────────

    val isWorkModeActive: Flow<Boolean> = preferences.map { it[KEY_WORK_MODE_ACTIVE] ?: false }

    val workModeEndTime: Flow<Long> = preferences.map { it[KEY_WORK_MODE_END_TIME] ?: 0L }

    val workModeWhitelistedApps: Flow<Set<String>> = preferences.map {
        val raw = it[KEY_WORK_MODE_WHITELISTED_APPS] ?: ""
        if (raw.isBlank()) emptySet() else raw.split(",").toSet()
    }

    val emergencyExitPhrase: Flow<String> = preferences.map { it[KEY_EMERGENCY_EXIT_PHRASE] ?: "" }

    // ─── Nudge ────────────────────────────────────────────────────────────────

    val nudgeLevel: Flow<Int> = preferences.map { it[KEY_NUDGE_LEVEL] ?: 0 }

    // ─── Onboarding ──────────────────────────────────────────────────────────

    val isOnboardingComplete: Flow<Boolean> = preferences.map { it[KEY_ONBOARDING_COMPLETE] ?: false }

    // ─── Exempt Apps ─────────────────────────────────────────────────────────

    val exemptApps: Flow<Set<String>> = preferences.map {
        val stored = it[KEY_EXEMPT_APPS]
        if (stored.isNullOrBlank()) DEFAULT_EXEMPT_APPS
        else stored.split(",").toSet() + DEFAULT_EXEMPT_APPS
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    val totalGatesPassed: Flow<Int> = preferences.map { it[KEY_TOTAL_GATES_PASSED] ?: 0 }
    val totalFocusMinutes: Flow<Long> = preferences.map { it[KEY_TOTAL_FOCUS_MINUTES] ?: 0L }
    val streakDays: Flow<Int> = preferences.map { it[KEY_STREAK_DAYS] ?: 0 }

    // ─── Setters ─────────────────────────────────────────────────────────────

    suspend fun setGateEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_GATE_ENABLED] = enabled }
    }

    suspend fun setCurrentIntent(intent: String, sessionId: String) {
        context.dataStore.edit {
            it[KEY_CURRENT_INTENT] = intent
            it[KEY_INTENT_TIMESTAMP] = System.currentTimeMillis()
            it[KEY_INTENT_SESSION_ID] = sessionId
            it[KEY_NUDGE_LEVEL] = 0 // Reset nudge on new intent
        }
    }

    suspend fun clearIntent() {
        context.dataStore.edit {
            it[KEY_CURRENT_INTENT] = ""
            it[KEY_INTENT_TIMESTAMP] = 0L
            it[KEY_NUDGE_LEVEL] = 0
        }
    }

    suspend fun startWorkMode(endTimeMillis: Long, whitelistedApps: Set<String>, exitPhrase: String) {
        context.dataStore.edit {
            it[KEY_WORK_MODE_ACTIVE] = true
            it[KEY_WORK_MODE_START_TIME] = System.currentTimeMillis()
            it[KEY_WORK_MODE_END_TIME] = endTimeMillis
            it[KEY_WORK_MODE_WHITELISTED_APPS] = whitelistedApps.joinToString(",")
            it[KEY_EMERGENCY_EXIT_PHRASE] = exitPhrase
        }
    }

    suspend fun endWorkMode() {
        context.dataStore.edit {
            it[KEY_WORK_MODE_ACTIVE] = false
            it[KEY_WORK_MODE_END_TIME] = 0L
            it[KEY_WORK_MODE_WHITELISTED_APPS] = ""
        }
    }

    suspend fun escalateNudge() {
        context.dataStore.edit {
            val current = it[KEY_NUDGE_LEVEL] ?: 0
            it[KEY_NUDGE_LEVEL] = minOf(current + 1, 3)
            it[KEY_NUDGE_COUNT_TODAY] = (it[KEY_NUDGE_COUNT_TODAY] ?: 0) + 1
        }
    }

    suspend fun resetNudge() {
        context.dataStore.edit { it[KEY_NUDGE_LEVEL] = 0 }
    }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETE] = true }
    }

    suspend fun incrementGatesPassed() {
        context.dataStore.edit {
            it[KEY_TOTAL_GATES_PASSED] = (it[KEY_TOTAL_GATES_PASSED] ?: 0) + 1
        }
    }

    suspend fun addFocusMinutes(minutes: Long) {
        context.dataStore.edit {
            it[KEY_TOTAL_FOCUS_MINUTES] = (it[KEY_TOTAL_FOCUS_MINUTES] ?: 0L) + minutes
        }
    }

    suspend fun saveApiKey(provider: String, key: String) {
        context.dataStore.edit {
            it[KEY_AI_PROVIDER] = provider
            when (provider) {
                "gemini" -> it[KEY_GEMINI_API_KEY] = key
                "openai" -> it[KEY_OPENAI_API_KEY] = key
            }
        }
    }

    suspend fun getApiKeyOnce(provider: String): String {
        // Synchronous read via runBlocking for one-shot access
        var key = ""
        context.dataStore.edit { prefs ->
            key = when (provider) {
                "openai" -> prefs[KEY_OPENAI_API_KEY] ?: ""
                else -> prefs[KEY_GEMINI_API_KEY] ?: ""
            }
        }
        return key
    }
}
