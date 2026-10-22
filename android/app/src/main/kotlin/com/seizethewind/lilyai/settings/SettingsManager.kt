package com.seizethewind.lilyai.settings

import android.content.Context

/** Which model answers: the local one, Groq, or local first with Groq as the backup. */
enum class EngineMode { AUTO, LOCAL, CLOUD }

/** Non-secret app settings. Secrets (API keys) live in [com.seizethewind.lilyai.api.ApiKeyStore]. */
class SettingsManager(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var groqModel: String
        get() = prefs.getString(KEY_GROQ_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_GROQ_MODEL
        set(value) {
            prefs.edit().putString(KEY_GROQ_MODEL, value).apply()
        }

    var engineMode: EngineMode
        get() = try {
            EngineMode.valueOf(prefs.getString(KEY_ENGINE_MODE, null) ?: EngineMode.AUTO.name)
        } catch (e: IllegalArgumentException) {
            EngineMode.AUTO
        }
        set(value) {
            prefs.edit().putString(KEY_ENGINE_MODE, value.name).apply()
        }

    companion object {
        const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
        private const val PREFS_NAME = "lilyai_settings"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_ENGINE_MODE = "engine_mode"
    }
}
