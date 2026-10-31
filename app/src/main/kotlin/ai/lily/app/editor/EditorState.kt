package ai.lily.app.editor

import ai.lily.app.lib.editor.DraftStore
import ai.lily.app.lib.editor.FindOptions
import ai.lily.app.lib.editor.Lang
import ai.lily.app.lib.editor.ProjectSearchResult
import ai.lily.app.lib.editor.UndoHistory
import ai.lily.app.lib.editor.buildFindRegex
import ai.lily.app.lib.editor.canPreview
import ai.lily.app.lib.editor.deleteLines
import ai.lily.app.lib.editor.detectIndent
import ai.lily.app.lib.editor.duplicateLines
import ai.lily.app.lib.editor.findMatches
import ai.lily.app.lib.editor.gotoLine
import ai.lily.app.lib.editor.insertAt
import ai.lily.app.lib.editor.languageFor
import ai.lily.app.lib.editor.replaceEvery
import ai.lily.app.lib.editor.replaceOne
import ai.lily.app.lib.editor.searchFiles
import ai.lily.app.lib.editor.shiftIndent
import ai.lily.app.lib.editor.smartEdit
import ai.lily.app.lib.editor.toggleLineComment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Files bigger than this are not opened. */
const val MAX_FILE_BYTES = 1_000_000L

/** Files with more characters than this open without syntax colours so typing stays smooth. */
const val MAX_HIGHLIGHT_CHARS = 150_000

private const val MAX_LISTED = 3_000
private const val AUTO_SAVE_AFTER_MS = 2_000L
private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea")

/** What the preview shows: nothing, half the screen under the editor, or the whole screen. */
enum class PreviewMode { OFF, SPLIT, FULL }

/** One open file. */
class EditorTab(val path: String, text: String) {
    val lang: Lang = languageFor(path)
    var value by mutableStateOf(TextFieldValue(text))
    var savedText by mutableStateOf(text)
    val history = UndoHistory(text)
    val dirty: Boolean get() = value.text != savedText
    val name: String get() = path.substringAfterLast('/')

    /** Counts every change to the text. The draft saver uses it to see what is new. */
    var editVersion = 0
    var draftedVersion = 0
    var lastEditAt = 0L

    /** Puts back typing that was never saved. It becomes the first undo step after the saved text. */
    fun restore(text: String) {
        val now = System.currentTimeMillis()
        value = TextFieldValue(text)
        history.record(text, 0, 0, now, boundary = true)
        editVersion++
        lastEditAt = now
    }
}

/** Everything the editor screen shows: open tabs, settings, the find bar and status messages. */
class EditorState(
    val root: File,
    private val drafts: DraftStore,
    private val prefs: EditorPrefs,
) {
    val tabs = mutableStateListOf<EditorTab>()
    var active by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)

    var settings by mutableStateOf(prefs.loadSettings())
        private set
    var fontSize by mutableStateOf(prefs.fontSize)
        private set
    var preview by mutableStateOf(PreviewMode.OFF)

    var findQuery by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var useRegex by mutableStateOf(false)
    var findInfo by mutableStateOf<String?>(null)

    private var pendingClose: EditorTab? = null
    private var sessionReady = false

    val current: EditorTab? get() = tabs.getOrNull(active)
    val theme: EditorTheme get() = editorThemeById(settings.theme)

    // ---- Settings and preview ----

    fun updateSettings(s: ai.lily.app.lib.editor.EditorSettings) {
        settings = s
        prefs.saveSettings(s)
    }

    fun updateFontSize(size: Int) {
        fontSize = size.coerceIn(EditorPrefs.MIN_FONT, EditorPrefs.MAX_FONT)
        prefs.fontSize = fontSize
    }

    /** Off, then half the screen, then the whole screen, then off again. */
    fun cyclePreview() {
        val tab = current
        if (tab == null || !canPreview(tab.path)) {
            message = "Preview works for HTML, SVG, Markdown and JavaScript files"
            return
        }
        preview = when (preview) {
            PreviewMode.OFF -> PreviewMode.SPLIT
            PreviewMode.SPLIT -> PreviewMode.FULL
            PreviewMode.FULL -> PreviewMode.OFF
        }
    }

    // ---- Files ----

    /** The file for a path relative to the workspace, or null if it would land outside the workspace. */
    private fun resolve(rel: String): File? {
        val base = root.canonicalFile
        val f = File(base, rel).canonicalFile
        return if (f.path.startsWith(base.path + File.separator)) f else null
    }

    suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        val base = root.canonicalFile
        base.walkTopDown()
            .onEnter { it == base || it.name !in SKIP_DIRS }
            .filter { it.isFile }
            .take(MAX_LISTED)
            .map { it.relativeTo(base).path }
            .sorted()
            .toList()
    }

    suspend fun open(rel: String) {
        val f = resolve(rel) ?: run {
            message = "That path is outside the workspace"
            return
        }
        val key = f.relativeTo(root.canonicalFile).path
        val existing = tabs.indexOfFirst { it.path == key }
        if (existing >= 0) {
            active = existing
            return
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    !f.isFile -> error("Not a file")
                    f.length() > MAX_FILE_BYTES -> error("File is over 1 MB")
                    else -> f.readText().replace("\r\n", "\n")
                }
            }
        }
        result.onSuccess { text ->
            val tab = EditorTab(key, text)
            restoreDraft(tab)
            tabs.add(tab)
            active = tabs.lastIndex
        }.onFailure {
            message = "Could not open $key: ${it.message}"
        }
    }

    /** Brings back unsaved typing from an earlier session, if it was typed on top of the text that is on disk now. */
    private fun restoreDraft(tab: EditorTab) {
        val draft = runCatching { drafts.load(tab.path) }.getOrNull() ?: return
        when {
            draft.text == tab.savedText -> runCatching { drafts.delete(tab.path) }
            draft.baseHash == tab.savedText.hashCode() -> {
                tab.restore(draft.text)
                message = "Restored unsaved typing in ${tab.name}"
            }
            else -> {
                runCatching { drafts.delete(tab.path) }
                message = "Dropped an old draft of ${tab.name} because the file changed"
            }
        }
    }

    /** Opens [rel] and selects the match at 1 based [line] and [column], [length] characters long. */
    suspend fun openAt(rel: String, line: Int, column: Int, length: Int) {
        open(rel)
        val tab = tabs.firstOrNull { it.path == rel } ?: return
        val text = tab.value.text
        var pos = 0
        var l = 1
        while (l < line) {
            val i = text.indexOf('\n', pos)
            if (i < 0) break
            pos = i + 1
            l++
        }
        val start = (pos + column - 1).coerceIn(0, text.length)
        val end = (start + length).coerceAtMost(text.length)
        tab.value = tab.value.copy(selection = TextRange(start, end))
        active = tabs.indexOf(tab)
    }

    suspend fun create(name: String) {
        val rel = name.trim().trimStart('/')
        if (rel.isEmpty()) return
        val f = resolve(rel) ?: run {
            message = "That name is outside the workspace"
            return
        }
        if (!f.exists()) {
            val made = runCatching {
                withContext(Dispatchers.IO) {
                    f.parentFile?.mkdirs()
                    f.createNewFile()
                }
            }
            if (made.isFailure) {
                message = "Could not create $rel: ${made.exceptionOrNull()?.message}"
                return
            }
        }
        open(rel)
    }

    suspend fun save(tab: EditorTab, quiet: Boolean = false) {
        val f = resolve(tab.path) ?: return
        val text = tab.value.text
        runCatching { withContext(Dispatchers.IO) { f.writeText(text) } }
            .onSuccess {
                tab.savedText = text
                if (!tab.dirty) runCatching { drafts.delete(tab.path) }
                if (!quiet) message = "Saved ${tab.path}"
            }
            .onFailure { message = "Could not save ${tab.path}: ${it.message}" }
    }

    suspend fun saveAll() {
        for (t in tabs.toList()) if (t.dirty) save(t)
    }

    /** A tab with unsaved typing needs a second tap to close, so typing is not lost by accident. */
    fun close(tab: EditorTab) {
        if (tab.dirty && pendingClose !== tab) {
            pendingClose = tab
            message = "${tab.name} has unsaved changes. Tap close again to discard them."
            return
        }
        pendingClose = null
        val i = tabs.indexOf(tab)
        if (i < 0) return
        tabs.removeAt(i)
        runCatching { drafts.delete(tab.path) }
        if (i < active) active--
        active = active.coerceIn(0, maxOf(0, tabs.lastIndex))
    }

    /** Moves to the next ([delta] = 1) or previous (-1) tab, wrapping around. */
    fun switchTab(delta: Int) {
        if (tabs.size < 2) return
        active = (active + delta + tabs.size) % tabs.size
    }

    // ---- Keeping work safe ----

    /**
     * Writes a draft for every tab whose text changed since the last time, and removes the draft of a tab that is back to
     * its saved text. Cheap when nothing changed. Safe to call from any thread.
     */
    @Synchronized
    fun flushDrafts() {
        for (t in tabs.toList()) {
            val version = t.editVersion
            if (version == t.draftedVersion) continue
            try {
                if (t.dirty) drafts.save(t.path, t.value.text, t.savedText.hashCode()) else drafts.delete(t.path)
                t.draftedVersion = version
            } catch (e: IOException) {
                // Try again on the next round.
            }
        }
    }

    /** Saves files whose typing stopped a couple of seconds ago, when auto save is on. */
    suspend fun autoSaveTick() {
        if (!settings.autoSave) return
        val now = System.currentTimeMillis()
        for (t in tabs.toList()) {
            if (t.dirty && now - t.lastEditAt > AUTO_SAVE_AFTER_MS) save(t, quiet = true)
        }
    }

    /** Remembers which tabs are open for the next start. Does nothing until the last session has been brought back. */
    fun saveSession() {
        if (!sessionReady) return
        prefs.saveSession(tabs.map { it.path }, current?.path)
    }

    /** Opens the tabs that were open last time. Files that were deleted since are skipped. */
    suspend fun restoreSession() {
        if (sessionReady) return
        if (tabs.isEmpty()) {
            val (paths, activePath) = prefs.loadSession()
            for (p in paths) {
                if (resolve(p)?.isFile == true) open(p)
            }
            val idx = tabs.indexOfFirst { it.path == activePath }
            if (idx >= 0) active = idx
        }
        sessionReady = true
    }

    // ---- Search in all files ----

    /** Searches every file in the workspace. Files with unsaved typing are searched as they are on screen. */
    suspend fun searchAll(options: FindOptions): ProjectSearchResult {
        val overrides = tabs.filter { it.dirty }.associate { it.path to it.value.text }
        return withContext(Dispatchers.IO) { searchFiles(root, options, overrides) }
    }

    // ---- Editing ----

    private fun setText(tab: EditorTab, v: TextFieldValue, boundary: Boolean) {
        val now = System.currentTimeMillis()
        tab.value = v
        tab.history.record(v.text, v.selection.start, v.selection.end, now, boundary)
        tab.editVersion++
        tab.lastEditAt = now
    }

    /** Called for every change the text field reports. */
    fun onEdit(tab: EditorTab, new: TextFieldValue) {
        val indent = settings.indentFor(detectIndent(new.text))
        val smart = smartEdit(tab.value, new, tab.lang, indent, settings.autoPair)
        setText(tab, smart ?: new, boundary = smart != null)
    }

    private fun command(f: (TextFieldValue, String) -> TextFieldValue) {
        val tab = current ?: return
        val indent = settings.indentFor(detectIndent(tab.value.text))
        setText(tab, f(tab.value, indent), boundary = true)
    }

    fun insert(s: String) = command { v, _ -> insertAt(v, s) }
    fun indent() = command { v, ind -> shiftIndent(v, ind, unindent = false) }
    fun unindent() = command { v, ind -> shiftIndent(v, ind, unindent = true) }
    fun duplicateLine() = command { v, _ -> duplicateLines(v) }
    fun deleteLine() = command { v, _ -> deleteLines(v) }
    fun goToLine(line: Int) = command { v, _ -> gotoLine(v, line) }

    fun toggleComment() {
        val prefix = current?.lang?.lineComment
        if (prefix == null) {
            message = "No line comment style for this file type"
            return
        }
        command { v, _ -> toggleLineComment(v, prefix) }
    }

    fun undo() {
        val tab = current ?: return
        tab.history.undo()?.let {
            tab.value = TextFieldValue(it.text, TextRange(it.selStart, it.selEnd))
            tab.editVersion++
            tab.lastEditAt = System.currentTimeMillis()
        }
    }

    fun redo() {
        val tab = current ?: return
        tab.history.redo()?.let {
            tab.value = TextFieldValue(it.text, TextRange(it.selStart, it.selEnd))
            tab.editVersion++
            tab.lastEditAt = System.currentTimeMillis()
        }
    }

    // ---- Find and replace ----

    private fun findRegex(): Regex? {
        if (findQuery.isEmpty()) {
            findInfo = null
            return null
        }
        return try {
            buildFindRegex(FindOptions(findQuery, caseSensitive = caseSensitive, regex = useRegex))
        } catch (e: IllegalArgumentException) {
            findInfo = "Invalid pattern"
            null
        }
    }

    fun findNext(forward: Boolean) {
        val tab = current ?: return
        val re = findRegex() ?: return
        val matches = findMatches(tab.value.text, re)
        if (matches.isEmpty()) {
            findInfo = "No matches"
            return
        }
        val sel = tab.value.selection
        val pick = if (forward) {
            matches.firstOrNull { it.first >= sel.max } ?: matches.first()
        } else {
            matches.lastOrNull { it.last + 1 <= sel.min } ?: matches.last()
        }
        tab.value = tab.value.copy(selection = TextRange(pick.first, pick.last + 1))
        findInfo = "${matches.indexOf(pick) + 1} of ${matches.size}"
    }

    /** Replaces the selected match, or just moves to the next match if the selection is not one. */
    fun replaceCurrent() {
        val tab = current ?: return
        val re = findRegex() ?: return
        val text = tab.value.text
        val sel = tab.value.selection
        val range = IntRange(sel.min, sel.max - 1)
        val onMatch = !sel.collapsed && findMatches(text, re).any { it == range }
        val replacement = if (onMatch) replaceOne(text, re, range, replaceText, useRegex) else null
        if (replacement == null) {
            findNext(true)
            return
        }
        val newText = text.replaceRange(sel.min, sel.max, replacement)
        setText(tab, TextFieldValue(newText, TextRange(sel.min + replacement.length)), boundary = true)
        findNext(true)
    }

    fun replaceAll() {
        val tab = current ?: return
        val re = findRegex() ?: return
        val text = tab.value.text
        val count = findMatches(text, re).size
        if (count == 0) {
            findInfo = "No matches"
            return
        }
        val newText = replaceEvery(text, re, replaceText, useRegex)
        val at = tab.value.selection.start.coerceAtMost(newText.length)
        setText(tab, TextFieldValue(newText, TextRange(at)), boundary = true)
        findInfo = "Replaced $count"
    }
}
