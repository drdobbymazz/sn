package dev.sn.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sn.core.AgentConfig
import dev.sn.core.DEFAULT_SYSTEM_PROMPT
import dev.sn.core.HostAddress
import dev.sn.core.OllamaConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("sn_settings")

/**
 * Tools that reach outside the phone, or that the user cannot undo.
 *
 * Changing this list is a real decision, so it lives in one obvious place
 * rather than being scattered across the tool definitions.
 */
val DEFAULT_CONFIRM_TOOLS = setOf(
    "sms_send",
    "call_place",
    "camera_photo",
    "calendar_create_event",
)

data class SnSettings(
    val ollamaHost: String = "",
    val model: String = "qwen3:8b",
    val temperature: Int = 40,           // percent, so it stores as an int
    val contextTokens: Int = 8192,
    val maxSteps: Int = 8,
    val historyMessages: Int = 40,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val confirmTools: Set<String> = DEFAULT_CONFIRM_TOOLS,
    val disabledTools: Set<String> = emptySet(),
    val proactiveEnabled: Boolean = false,
    val proactiveIntervalMinutes: Int = 60,
    val quietHoursStart: Int = 23,
    val quietHoursEnd: Int = 7,
) {
    val isConfigured: Boolean get() = ollamaHost.isNotBlank()

    fun ollama(): OllamaConfig = OllamaConfig(
        host = ollamaHost,
        model = model,
        temperature = temperature / 100.0,
        contextTokens = contextTokens,
    )

    fun agent(): AgentConfig = AgentConfig(
        systemPrompt = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT },
        maxSteps = maxSteps,
        confirmTools = confirmTools,
        disabledTools = disabledTools,
    )

    /** True when the clock is inside the do-not-disturb window. */
    fun inQuietHours(hour: Int): Boolean =
        if (quietHoursStart == quietHoursEnd) {
            false
        } else if (quietHoursStart < quietHoursEnd) {
            hour in quietHoursStart until quietHoursEnd
        } else {
            // The window wraps midnight, e.g. 23:00 to 07:00.
            hour >= quietHoursStart || hour < quietHoursEnd
        }
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("ollama_host")
        val MODEL = stringPreferencesKey("model")
        val TEMPERATURE = intPreferencesKey("temperature")
        val CONTEXT = intPreferencesKey("context_tokens")
        val MAX_STEPS = intPreferencesKey("max_steps")
        val HISTORY = intPreferencesKey("history_messages")
        val PROMPT = stringPreferencesKey("system_prompt")
        val CONFIRM = stringSetPreferencesKey("confirm_tools")
        val DISABLED = stringSetPreferencesKey("disabled_tools")
        val PROACTIVE = booleanPreferencesKey("proactive_enabled")
        val PROACTIVE_INTERVAL = intPreferencesKey("proactive_interval")
        val QUIET_START = intPreferencesKey("quiet_start")
        val QUIET_END = intPreferencesKey("quiet_end")
    }

    val settings: Flow<SnSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): SnSettings {
        val defaults = SnSettings()
        return SnSettings(
            ollamaHost = this[Keys.HOST] ?: defaults.ollamaHost,
            model = this[Keys.MODEL] ?: defaults.model,
            temperature = this[Keys.TEMPERATURE] ?: defaults.temperature,
            contextTokens = this[Keys.CONTEXT] ?: defaults.contextTokens,
            maxSteps = this[Keys.MAX_STEPS] ?: defaults.maxSteps,
            historyMessages = this[Keys.HISTORY] ?: defaults.historyMessages,
            systemPrompt = this[Keys.PROMPT] ?: defaults.systemPrompt,
            confirmTools = this[Keys.CONFIRM] ?: defaults.confirmTools,
            disabledTools = this[Keys.DISABLED] ?: defaults.disabledTools,
            proactiveEnabled = this[Keys.PROACTIVE] ?: defaults.proactiveEnabled,
            proactiveIntervalMinutes = this[Keys.PROACTIVE_INTERVAL] ?: defaults.proactiveIntervalMinutes,
            quietHoursStart = this[Keys.QUIET_START] ?: defaults.quietHoursStart,
            quietHoursEnd = this[Keys.QUIET_END] ?: defaults.quietHoursEnd,
        )
    }

    suspend fun setHost(value: String) = edit { prefs ->
        // Accept "laptop.tail1234.ts.net" as readily as a full URL; getting
        // this wrong is the single most likely setup mistake.
        prefs[Keys.HOST] = normalizeHost(value)
    }

    suspend fun setModel(value: String) = edit { it[Keys.MODEL] = value.trim() }
    suspend fun setTemperature(percent: Int) = edit { it[Keys.TEMPERATURE] = percent.coerceIn(0, 100) }
    suspend fun setContextTokens(value: Int) = edit { it[Keys.CONTEXT] = value.coerceIn(1024, 131072) }
    suspend fun setMaxSteps(value: Int) = edit { it[Keys.MAX_STEPS] = value.coerceIn(1, 30) }
    suspend fun setHistoryMessages(value: Int) = edit { it[Keys.HISTORY] = value.coerceIn(2, 400) }
    suspend fun setSystemPrompt(value: String) = edit { it[Keys.PROMPT] = value }
    suspend fun setConfirmTools(value: Set<String>) = edit { it[Keys.CONFIRM] = value }
    suspend fun setDisabledTools(value: Set<String>) = edit { it[Keys.DISABLED] = value }
    suspend fun setProactive(enabled: Boolean) = edit { it[Keys.PROACTIVE] = enabled }
    suspend fun setProactiveInterval(minutes: Int) =
        edit { it[Keys.PROACTIVE_INTERVAL] = minutes.coerceIn(15, 1440) }

    suspend fun setQuietHours(start: Int, end: Int) = edit {
        it[Keys.QUIET_START] = start.coerceIn(0, 23)
        it[Keys.QUIET_END] = end.coerceIn(0, 23)
    }

    suspend fun toggleConfirm(tool: String, required: Boolean) = edit { prefs ->
        val current = prefs[Keys.CONFIRM] ?: DEFAULT_CONFIRM_TOOLS
        prefs[Keys.CONFIRM] = if (required) current + tool else current - tool
    }

    suspend fun toggleDisabled(tool: String, disabled: Boolean) = edit { prefs ->
        val current = prefs[Keys.DISABLED] ?: emptySet()
        prefs[Keys.DISABLED] = if (disabled) current + tool else current - tool
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    companion object {
        /** Delegates to the core helper, which is unit tested. */
        fun normalizeHost(raw: String): String = HostAddress.normalize(raw)
    }
}
