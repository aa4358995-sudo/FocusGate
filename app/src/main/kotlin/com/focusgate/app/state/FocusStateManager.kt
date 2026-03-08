package com.focusgate.app.state

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "focus_state")

/**
 * FocusStateManager
 * ─────────────────
 * The single source of truth for all FocusGate state.
 * Uses DataStore for persistence, meaning state survives:
 *   • App process being killed
 *   • Device reboot (re-read on boot)
 *   • Activity recreation
 *
 * State is exposed as Flows for reactive UI updates.
 */
class FocusStateManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dataStore = context.dataStore

    // ─── Preference Keys ────────────────────────────────────────────────────

    companion object Keys {
        // Gate state
        val IS_GATE_ENABLED          = booleanPreferencesKey("gate_enabled")
        val CURRENT_INTENT_TEXT      = stringPreferencesKey("current_intent")
        val INTENT_TIMESTAMP         = longPreferencesKey("intent_timestamp")
        val INTENT_SESSION_ID        = stringPreferencesKey("intent_session_id")

        // Work Mode state
        val WORK_MODE_ACTIVE         = booleanPreferencesKey("work_mode_active")
        val WORK_MODE_END_TIME       = longPreferencesKey("work_mode_end_time")
        val WORK_MODE_ALLOWED_APPS   = stringPreferencesKey("work_mode_allowed_apps") // comma-separated pkg names
        val WORK_MODE_LABEL          = stringPreferencesKey("work_mode_label")

        // Nudge state
        val NUDGE_LEVEL              = intPreferencesKey("nudge_level")     // 0=none, 1=warn, 2=gray, 3=block
        val NUDGE_TRIGGER_COUNT      = intPreferencesKey("nudge_trigger_count")
        val LAST_NUDGE_TIMESTAMP     = longPreferencesKey("last_nudge_time")

        // Onboarding
        val ONBOARDING_COMPLETE      = booleanPreferencesKey("onboarding_done")
        val GEMINI_API_KEY           = stringPreferencesKey("gemini_api_key")

        // Exceptions: apps that bypass the gate entirely
        val EXCEPTION_APPS           = stringPreferencesKey("exception_apps")

        // Stats
        val TOTAL_SESSIONS_COMPLETED = intPreferencesKey("sessions_completed")
        val TOTAL_MINUTES_FOCUSED    = intPreferencesKey("minutes_focused")
        val STREAK_DAYS              = intPreferencesKey("streak_days")
        val LAST_SESSION_DATE        = stringPreferencesKey("last_session_date")
    }

    // ─── Observable State Flows ──────────────────────────────────────────────

    val isGateEnabled: Flow<Boolean> = dataStore.data
        .map { it[IS_GATE_ENABLED] ?: true }
        .distinctUntilChanged()

    val currentIntent: Flow<String?> = dataStore.data
        .map { it[CURRENT_INTENT_TEXT]?.takeIf { s -> s.isNotBlank() } }
        .distinctUntilChanged()

    val workModeState: Flow<WorkModeState> = dataStore.data
        .map { prefs ->
            WorkModeState(
                isActive      = prefs[WORK_MODE_ACTIVE] ?: false,
                endTimeMillis = prefs[WORK_MODE_END_TIME] ?: 0L,
                allowedApps   = prefs[WORK_MODE_ALLOWED_APPS]
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
                label         = prefs[WORK_MODE_LABEL] ?: "Focus Session"
            )
        }
        .distinctUntilChanged()

    val nudgeLevel: Flow<NudgeLevel> = dataStore.data
        .map { NudgeLevel.fromInt(it[NUDGE_LEVEL] ?: 0) }
        .distinctUntilChanged()

    val onboardingComplete: Flow<Boolean> = dataStore.data
        .map { it[ONBOARDING_COMPLETE] ?: false }
        .distinctUntilChanged()

    val geminiApiKey: Flow<String?> = dataStore.data
        .map { it[GEMINI_API_KEY]?.takeIf { k -> k.isNotBlank() } }

    val focusStats: Flow<FocusStats> = dataStore.data
        .map { prefs ->
            FocusStats(
                sessionsCompleted = prefs[TOTAL_SESSIONS_COMPLETED] ?: 0,
                minutesFocused    = prefs[TOTAL_MINUTES_FOCUSED] ?: 0,
                streakDays        = prefs[STREAK_DAYS] ?: 0
            )
        }

    // ─── State Mutations ─────────────────────────────────────────────────────

    fun setCurrentIntent(intent: String, sessionId: String) {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[CURRENT_INTENT_TEXT]  = intent
                prefs[INTENT_TIMESTAMP]     = System.currentTimeMillis()
                prefs[INTENT_SESSION_ID]    = sessionId
                prefs[NUDGE_LEVEL]          = 0 // Reset nudge on new intent
            }
        }
    }

    fun clearCurrentIntent() {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[CURRENT_INTENT_TEXT] = ""
                prefs[NUDGE_LEVEL]         = 0
            }
        }
    }

    fun startWorkMode(durationMinutes: Int, allowedApps: List<String>, label: String) {
        scope.launch {
            val endTime = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
            dataStore.edit { prefs ->
                prefs[WORK_MODE_ACTIVE]       = true
                prefs[WORK_MODE_END_TIME]     = endTime
                prefs[WORK_MODE_ALLOWED_APPS] = allowedApps.joinToString(",")
                prefs[WORK_MODE_LABEL]        = label
                prefs[NUDGE_LEVEL]            = 0
            }
        }
    }

    fun endWorkMode() {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[WORK_MODE_ACTIVE]       = false
                prefs[WORK_MODE_ALLOWED_APPS] = ""
            }
            incrementSessionStats()
        }
    }

    fun escalateNudge() {
        scope.launch {
            dataStore.edit { prefs ->
                val current = prefs[NUDGE_LEVEL] ?: 0
                val newLevel = (current + 1).coerceAtMost(NudgeLevel.BLOCK.value)
                prefs[NUDGE_LEVEL]          = newLevel
                prefs[NUDGE_TRIGGER_COUNT]  = (prefs[NUDGE_TRIGGER_COUNT] ?: 0) + 1
                prefs[LAST_NUDGE_TIMESTAMP] = System.currentTimeMillis()
            }
        }
    }

    fun resetNudge() {
        scope.launch {
            dataStore.edit { prefs ->
                prefs[NUDGE_LEVEL] = 0
            }
        }
    }

    fun setGateEnabled(enabled: Boolean) {
        scope.launch {
            dataStore.edit { it[IS_GATE_ENABLED] = enabled }
        }
    }

    fun saveGeminiApiKey(key: String) {
        scope.launch {
            dataStore.edit { it[GEMINI_API_KEY] = key }
        }
    }

    fun setOnboardingComplete() {
        scope.launch {
            dataStore.edit { it[ONBOARDING_COMPLETE] = true }
        }
    }

    fun getExceptionApps(): Flow<List<String>> = dataStore.data
        .map { prefs ->
            val defaults = listOf(
                "com.android.phone",         // Phone calls
                "com.android.dialer",         // Dialer
                "com.google.android.dialer",  // Google Phone
                "com.android.camera",         // Camera
                "com.android.camera2",
                "com.google.android.camera",
                "com.android.contacts",
                "com.android.emergency"
            )
            val userAdded = prefs[EXCEPTION_APPS]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            defaults + userAdded
        }

    // ─── Synchronous reads (for services that can't use coroutines easily) ──

    suspend fun isWorkModeActiveNow(): Boolean {
        val prefs = dataStore.data.first()
        val active = prefs[WORK_MODE_ACTIVE] ?: false
        val endTime = prefs[WORK_MODE_END_TIME] ?: 0L
        return active && System.currentTimeMillis() < endTime
    }

    suspend fun getAllowedAppsNow(): List<String> {
        val prefs = dataStore.data.first()
        return prefs[WORK_MODE_ALLOWED_APPS]
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    suspend fun getCurrentIntentNow(): String? {
        return dataStore.data.first()[CURRENT_INTENT_TEXT]?.takeIf { it.isNotBlank() }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private suspend fun incrementSessionStats() {
        dataStore.edit { prefs ->
            prefs[TOTAL_SESSIONS_COMPLETED] = (prefs[TOTAL_SESSIONS_COMPLETED] ?: 0) + 1
            val startedAt = prefs[WORK_MODE_END_TIME]?.minus(
                (prefs[WORK_MODE_END_TIME] ?: 0L)
            ) ?: 0L
            // Approximate: add 1 min per session as minimum
            prefs[TOTAL_MINUTES_FOCUSED] = (prefs[TOTAL_MINUTES_FOCUSED] ?: 0) + 1
        }
    }
}

// ─── Data Models ─────────────────────────────────────────────────────────────

data class WorkModeState(
    val isActive: Boolean,
    val endTimeMillis: Long,
    val allowedApps: List<String>,
    val label: String
) {
    val isExpired: Boolean get() = isActive && System.currentTimeMillis() > endTimeMillis
    val remainingMillis: Long get() = (endTimeMillis - System.currentTimeMillis()).coerceAtLeast(0)
    val remainingMinutes: Int get() = (remainingMillis / 60000).toInt()
}

data class FocusStats(
    val sessionsCompleted: Int,
    val minutesFocused: Int,
    val streakDays: Int
)

enum class NudgeLevel(val value: Int) {
    NONE(0),
    WARNING(1),
    GRAYSCALE(2),
    BLOCK(3);

    companion object {
        fun fromInt(v: Int) = entries.firstOrNull { it.value == v } ?: NONE
    }
}
