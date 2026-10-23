package com.seizethewind.lilyai.settings

import android.content.Context

/** Which model answers: the local one, Groq, or local first with Groq as the backup. */
enum class EngineMode { AUTO, LOCAL, CLOUD }

/** Non-secret app settings. Secrets (API keys, tokens) live in [com.seizethewind.lilyai.api.ApiKeyStore]. */
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

    /** Off by default: memories are only added to prompts sent to Groq if the user turns this on. */
    var shareMemoryWithCloud: Boolean
        get() = prefs.getBoolean(KEY_SHARE_MEMORY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SHARE_MEMORY, value).apply()
        }

    /** Lets the model use tools (time, memories). Anything that changes something still asks first. */
    var toolsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOOLS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TOOLS, value).apply()
        }

    /** Show the floating Stop button over other apps while LilyAi works (needs the overlay permission). */
    var showStopOverlay: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY, value).apply()
        }

    /** Newest memory timestamp already received from the cloud. */
    var lastPull: Long
        get() = prefs.getLong(KEY_LAST_PULL, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_PULL, value).apply()
        }

    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_BACKUP, value).apply()
        }

    companion object {
        const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
        private const val PREFS_NAME = "lilyai_settings"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_ENGINE_MODE = "engine_mode"
        private const val KEY_SHARE_MEMORY = "share_memory_cloud"
        private const val KEY_TOOLS = "tools_enabled"
        private const val KEY_OVERLAY = "show_stop_overlay"
        private const val KEY_LAST_PULL = "last_pull"
        private const val KEY_LAST_BACKUP = "last_backup"
    }
}
