package ai.lily.app.editor

import ai.lily.app.lib.editor.EditorSettings
import ai.lily.app.lib.editor.LineSpacing
import android.content.Context

/**
 * The editor's settings and the list of open tabs, kept in the app's private preferences. The open tabs are remembered per
 * [workspace] (empty for the phone's own workspace, the connection id for a remote one), so one workspace never reopens another's tabs.
 */
class EditorPrefs(context: Context, private val workspace: String = "") {
    private val sp = context.getSharedPreferences("editor_prefs", Context.MODE_PRIVATE)

    var fontSize: Int
        get() = sp.getInt("fontSize", 14).coerceIn(MIN_FONT, MAX_FONT)
        set(value) {
            sp.edit().putInt("fontSize", value.coerceIn(MIN_FONT, MAX_FONT)).apply()
        }

    fun loadSettings(): EditorSettings {
        val d = EditorSettings()
        return EditorSettings(
            theme = sp.getString("theme", d.theme) ?: d.theme,
            spacing = LineSpacing.fromName(sp.getString("spacing", null)),
            tabSize = sp.getInt("tabSize", d.tabSize),
            wordWrap = sp.getBoolean("wordWrap", d.wordWrap),
            lineNumbers = sp.getBoolean("lineNumbers", d.lineNumbers),
            autoPair = sp.getBoolean("autoPair", d.autoPair),
            autoSave = sp.getBoolean("autoSave", d.autoSave),
            font = sp.getString("font", d.font) ?: d.font,
        )
    }

    fun saveSettings(s: EditorSettings) {
        sp.edit()
            .putString("theme", s.theme)
            .putString("spacing", s.spacing.name)
            .putInt("tabSize", s.tabSize)
            .putBoolean("wordWrap", s.wordWrap)
            .putBoolean("lineNumbers", s.lineNumbers)
            .putBoolean("autoPair", s.autoPair)
            .putBoolean("autoSave", s.autoSave)
            .putString("font", s.font)
            .apply()
    }

    /** The paths of the tabs that were open, in order, and which one was active. */
    fun loadSession(): Pair<List<String>, String?> {
        val paths = (sp.getString("sessionPaths$workspace", "") ?: "").split('\n').filter { it.isNotEmpty() }
        return Pair(paths, sp.getString("sessionActive$workspace", null))
    }

    fun saveSession(paths: List<String>, active: String?) {
        sp.edit()
            .putString("sessionPaths$workspace", paths.filter { '\n' !in it }.joinToString("\n"))
            .putString("sessionActive$workspace", active)
            .apply()
    }

    companion object {
        const val MIN_FONT = 10
        const val MAX_FONT = 24
    }
}
