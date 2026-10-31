package ai.lily.app.editor

import ai.lily.app.lib.editor.FindOptions
import ai.lily.app.lib.editor.Lang
import ai.lily.app.lib.editor.UndoHistory
import ai.lily.app.lib.editor.buildFindRegex
import ai.lily.app.lib.editor.deleteLines
import ai.lily.app.lib.editor.detectIndent
import ai.lily.app.lib.editor.duplicateLines
import ai.lily.app.lib.editor.findMatches
import ai.lily.app.lib.editor.gotoLine
import ai.lily.app.lib.editor.insertAt
import ai.lily.app.lib.editor.languageFor
import ai.lily.app.lib.editor.replaceEvery
import ai.lily.app.lib.editor.replaceOne
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

/** Files bigger than this are not opened. */
const val MAX_FILE_BYTES = 1_000_000L

/** Files with more characters than this open without syntax colours so typing stays smooth. */
const val MAX_HIGHLIGHT_CHARS = 150_000

private const val MAX_LISTED = 3_000
private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea")

/** One open file. */
class EditorTab(val path: String, text: String) {
    val lang: Lang = languageFor(path)
    var value by mutableStateOf(TextFieldValue(text))
    var savedText by mutableStateOf(text)
    val history = UndoHistory(text)
    val dirty: Boolean get() = value.text != savedText
    val name: String get() = path.substringAfterLast('/')
}

/** Everything the editor screen shows: open tabs, the find bar and status messages. */
class EditorState(private val root: File) {
    val tabs = mutableStateListOf<EditorTab>()
    var active by mutableStateOf(0)
    var message by mutableStateOf<String?>(null)

    var findQuery by mutableStateOf("")
    var replaceText by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var useRegex by mutableStateOf(false)
    var findInfo by mutableStateOf<String?>(null)

    private var pendingClose: EditorTab? = null

    val current: EditorTab? get() = tabs.getOrNull(active)

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
        result.onSuccess {
            tabs.add(EditorTab(key, it))
            active = tabs.lastIndex
        }.onFailure {
            message = "Could not open $key: ${it.message}"
        }
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

    suspend fun save(tab: EditorTab) {
        val f = resolve(tab.path) ?: return
        val text = tab.value.text
        runCatching { withContext(Dispatchers.IO) { f.writeText(text) } }
            .onSuccess {
                tab.savedText = text
                message = "Saved ${tab.path}"
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
        if (i < active) active--
        active = active.coerceIn(0, maxOf(0, tabs.lastIndex))
    }

    // ---- Editing ----

    private fun setText(tab: EditorTab, v: TextFieldValue, boundary: Boolean) {
        tab.value = v
        tab.history.record(v.text, v.selection.start, v.selection.end, System.currentTimeMillis(), boundary)
    }

    /** Called for every change the text field reports. */
    fun onEdit(tab: EditorTab, new: TextFieldValue) {
        val smart = smartEdit(tab.value, new, tab.lang, detectIndent(new.text))
        setText(tab, smart ?: new, boundary = smart != null)
    }

    private fun command(f: (TextFieldValue, String) -> TextFieldValue) {
        val tab = current ?: return
        setText(tab, f(tab.value, detectIndent(tab.value.text)), boundary = true)
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
        tab.history.undo()?.let { tab.value = TextFieldValue(it.text, TextRange(it.selStart, it.selEnd)) }
    }

    fun redo() {
        val tab = current ?: return
        tab.history.redo()?.let { tab.value = TextFieldValue(it.text, TextRange(it.selStart, it.selEnd)) }
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
