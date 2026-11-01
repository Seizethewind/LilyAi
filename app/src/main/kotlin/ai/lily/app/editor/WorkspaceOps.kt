package ai.lily.app.editor

import ai.lily.app.lib.editor.DraftStore
import ai.lily.app.lib.editor.FileReplace
import ai.lily.app.lib.editor.FindOptions
import ai.lily.app.lib.editor.SEARCH_SKIPPED_DIRS
import ai.lily.app.lib.editor.buildFindRegex
import ai.lily.app.lib.editor.isProbablyBinary
import ai.lily.app.lib.editor.planReplace
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_ENTRIES = 3_000

/**
 * What the person can do to the workspace itself: make folders, rename and delete files and folders, and replace text across
 * files. Open tabs are kept in step: a tab for something renamed is reopened under its new name, a tab for something deleted
 * is closed. Nothing leaves the workspace folder.
 */
class WorkspaceOps(private val state: EditorState, private val drafts: DraftStore) {

    private fun resolve(rel: String): File? {
        val base = state.root.canonicalFile
        val f = File(base, rel).canonicalFile
        return if (f.path.startsWith(base.path + File.separator)) f else null
    }

    private fun clean(name: String): String = name.trim().trim('/')

    private fun tabsUnder(rel: String): List<EditorTab> =
        state.tabs.filter { it.path == rel || it.path.startsWith("$rel/") }

    /** Closes a tab without asking and forgets its draft. */
    private fun dropTab(tab: EditorTab) {
        val i = state.tabs.indexOf(tab)
        if (i < 0) return
        state.tabs.removeAt(i)
        runCatching { drafts.delete(tab.path) }
        if (i < state.active) state.active--
        state.active = state.active.coerceIn(0, maxOf(0, state.tabs.lastIndex))
    }

    /** Files and folders (folders end in a slash), for choosing what to rename or delete. */
    suspend fun listEntries(): List<String> = withContext(Dispatchers.IO) {
        val base = state.root.canonicalFile
        base.walkTopDown()
            .onEnter { it == base || it.name !in SEARCH_SKIPPED_DIRS }
            .filter { it != base }
            .take(MAX_ENTRIES)
            .map { entry ->
                val rel = entry.relativeTo(base).path
                if (entry.isDirectory) "$rel/" else rel
            }
            .sorted()
            .toList()
    }

    suspend fun newFolder(name: String) {
        val rel = clean(name)
        if (rel.isEmpty()) return
        val dir = resolve(rel) ?: run {
            state.message = "That name is outside the workspace"
            return
        }
        if (dir.exists()) {
            state.message = "$rel already exists"
            return
        }
        val made = withContext(Dispatchers.IO) { dir.mkdirs() }
        state.message = if (made) "Created folder $rel" else "Could not create $rel"
    }

    /** Renames or moves a file or folder. Refuses if a tab under it has unsaved typing. */
    suspend fun rename(oldName: String, newName: String) {
        val old = clean(oldName)
        val renamed = clean(newName)
        if (old.isEmpty() || renamed.isEmpty() || old == renamed) return
        val src = resolve(old)
        val dest = resolve(renamed)
        if (src == null || dest == null) {
            state.message = "That name is outside the workspace"
            return
        }
        if (!src.exists()) {
            state.message = "$old does not exist"
            return
        }
        if (dest.exists()) {
            state.message = "$renamed already exists"
            return
        }
        if (dest.path.startsWith(src.path + File.separator)) {
            state.message = "A folder cannot be moved into itself"
            return
        }
        val affected = tabsUnder(old)
        val unsaved = affected.firstOrNull { it.dirty }
        if (unsaved != null) {
            state.message = "Save or close ${unsaved.name} before renaming it"
            return
        }
        val moved = withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            src.renameTo(dest)
        }
        if (!moved) {
            state.message = "Could not rename $old"
            return
        }
        val activePath = state.current?.path
        val reopen = affected.map { renamed + it.path.removePrefix(old) }
        val keep = if (activePath != null && affected.any { it.path == activePath }) {
            renamed + activePath.removePrefix(old)
        } else {
            activePath
        }
        affected.forEach { dropTab(it) }
        for (p in reopen) state.open(p)
        val idx = state.tabs.indexOfFirst { it.path == keep }
        if (idx >= 0) state.active = idx
        state.message = "Renamed $old to $renamed"
    }

    /** A sentence for the confirmation: what will be deleted and what will be lost with it. */
    suspend fun describe(name: String): String {
        val rel = clean(name)
        val target = resolve(rel) ?: return "That name is outside the workspace."
        val unsaved = tabsUnder(rel).filter { it.dirty }.map { it.name }
        val what = if (target.isDirectory) {
            val n = withContext(Dispatchers.IO) { target.walkTopDown().count { it.isFile } }
            val plural = if (n == 1) "" else "s"
            "The folder $rel and its $n file$plural will be deleted."
        } else {
            "The file $rel will be deleted."
        }
        val extra = if (unsaved.isEmpty()) "" else " Unsaved typing in ${unsaved.joinToString(", ")} will be lost."
        return "$what$extra This cannot be undone."
    }

    /** Deletes a file or folder and closes its tabs. The caller has already asked the person to confirm. */
    suspend fun delete(name: String) {
        val rel = clean(name)
        if (rel.isEmpty()) return
        val target = resolve(rel) ?: run {
            state.message = "That name is outside the workspace"
            return
        }
        if (!target.exists()) {
            state.message = "$rel does not exist"
            return
        }
        val affected = tabsUnder(rel)
        val deleted = withContext(Dispatchers.IO) { target.deleteRecursively() }
        affected.forEach { dropTab(it) }
        state.message = if (deleted) "Deleted $rel" else "Could not delete everything in $rel"
    }

    private fun readForReplace(file: File): String? {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return if (isProbablyBinary(text)) null else text
    }

    /**
     * Replaces every match in the given files. A file open in a tab changes in the editor as one undo step and is not saved;
     * any other file is written to disk. Returns a sentence saying what happened.
     */
    suspend fun replaceInFiles(options: FindOptions, replacement: String, paths: Collection<String>): String {
        val regex = try {
            buildFindRegex(options)
        } catch (e: IllegalArgumentException) {
            return "Invalid pattern"
        }
        var total = 0
        var files = 0
        var inTabs = 0
        var written = 0
        var failed = 0
        for (path in paths) {
            val tab = state.tabs.firstOrNull { it.path == path }
            val file = resolve(path)
            val plan: FileReplace? = try {
                when {
                    tab != null -> planReplace(tab.value.text, regex, replacement, options.regex)
                    file == null -> null
                    else -> withContext(Dispatchers.IO) {
                        readForReplace(file)?.let { planReplace(it, regex, replacement, options.regex) }
                    }
                }
            } catch (e: IllegalArgumentException) {
                return "The replacement text is not valid for this pattern"
            } catch (e: IndexOutOfBoundsException) {
                return "The replacement text uses a group the pattern does not have"
            }
            if (plan == null) continue
            if (tab != null) {
                val now = System.currentTimeMillis()
                val at = tab.value.selection.start.coerceAtMost(plan.newText.length)
                tab.value = TextFieldValue(plan.newText, TextRange(at))
                tab.history.record(plan.newText, at, at, now, boundary = true)
                tab.editVersion++
                tab.lastEditAt = now
                inTabs++
            } else if (file != null) {
                val ok = runCatching { withContext(Dispatchers.IO) { file.writeText(plan.newText) } }.isSuccess
                if (!ok) {
                    failed++
                    continue
                }
                written++
            } else {
                continue
            }
            total += plan.count
            files++
        }
        if (files == 0 && failed == 0) return "No matches to replace"
        return buildString {
            append("Replaced $total matches in $files files.")
            if (inTabs > 0) append(" $inTabs open tab(s) changed but not saved; undo works.")
            if (written > 0) append(" $written file(s) written to disk.")
            if (failed > 0) append(" $failed file(s) could not be written.")
        }
    }
}
