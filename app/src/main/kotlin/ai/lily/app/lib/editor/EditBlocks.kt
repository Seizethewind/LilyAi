package ai.lily.app.lib.editor

// Edit blocks: how a model reply says which parts of which files to change. Plain text, so it works with any model.
//
//   path/to/file.kt
//   <<<<<<< SEARCH
//   old lines
//   =======
//   new lines
//   >>>>>>> REPLACE
//
// An empty SEARCH creates a new file. An empty REPLACE deletes the lines.

/** One block of a reply. [searchEmpty] and [replaceEmpty] tell an empty section apart from one that holds a single empty line. */
data class FileEdit(
    val path: String,
    val search: String,
    val replace: String,
    val searchEmpty: Boolean,
    val replaceEmpty: Boolean,
)

/** [edits] are the blocks found in a reply and [prose] is what is left of the reply once the blocks are taken out. */
data class ParsedReply(val edits: List<FileEdit>, val prose: String)

private val KNOWN_NAMES = setOf("Makefile", "Dockerfile", "gradlew", "LICENSE", "Procfile")
private val BLANK_RUNS = Regex("\n{3,}")

/** True for a plain relative path inside the workspace. Rejects absolute paths, `..`, drive letters and backslashes. */
fun isSafePath(path: String): Boolean =
    path.isNotBlank() && path.length <= 300 && !path.startsWith("/") && !path.contains("..") &&
        !path.contains('\\') && !path.contains(':') && !path.contains('\u0000')

private fun isFence(line: String): Boolean = line.trim().startsWith("```")

private fun isSearchMarker(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("<<<<<<<") && t.contains("SEARCH")
}

private fun isDivider(line: String): Boolean = line.trim() == "======="

private fun isReplaceMarker(line: String): Boolean = line.trim().startsWith(">>>>>>>")

/** The file path a line names, allowing for the decoration models like to add (backticks, bold, a File: prefix). Null if it is not a path. */
private fun pathFrom(line: String): String? {
    var s = line.trim()
    if (s.isEmpty()) return null
    s = s.trim('*', '`', '#', ' ')
    for (prefix in listOf("File:", "file:", "FILE:", "Path:", "path:")) {
        if (s.startsWith(prefix)) s = s.removePrefix(prefix).trim()
    }
    s = s.trim('*', '`', ' ').removeSuffix(":")
    if (s.isEmpty() || s.any { it.isWhitespace() }) return null
    val name = s.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    val ext = if (dot >= 0) name.substring(dot + 1) else ""
    val looksLikeFile = name in KNOWN_NAMES || (ext.isNotEmpty() && ext.length <= 12 && ext.all { it.isLetterOrDigit() })
    return if (looksLikeFile) s else null
}

/** Finds every edit block in [text]. A block that is never closed, or has no file path in front of it, is ignored. */
fun parseReply(text: String): ParsedReply {
    val lines = text.replace("\r\n", "\n").split('\n')
    val edits = ArrayList<FileEdit>()
    val prose = ArrayList<String>()
    var path: String? = null
    var pathIdx = -1
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (isSearchMarker(line)) {
            val search = ArrayList<String>()
            val replace = ArrayList<String>()
            i++
            while (i < lines.size && !isDivider(lines[i])) {
                search += lines[i]
                i++
            }
            if (i >= lines.size) break
            i++
            while (i < lines.size && !isReplaceMarker(lines[i])) {
                replace += lines[i]
                i++
            }
            if (i >= lines.size) break
            i++
            val p = path
            if (p != null) {
                edits += FileEdit(p, search.joinToString("\n"), replace.joinToString("\n"), search.isEmpty(), replace.isEmpty())
                if (pathIdx in prose.indices) prose.removeAt(pathIdx)
                pathIdx = -1
            }
            continue
        }
        if (isFence(line)) {
            i++
            continue
        }
        prose += line
        val candidate = pathFrom(line)
        if (candidate != null) {
            path = candidate
            pathIdx = prose.size - 1
        }
        i++
    }
    return ParsedReply(edits, BLANK_RUNS.replace(prose.joinToString("\n").trim(), "\n\n"))
}

fun parseEditBlocks(text: String): List<FileEdit> = parseReply(text).edits

/** [text] is the file after the edits. If anything went wrong it is the original text and [errors] says what. */
data class ApplyOutcome(val text: String, val errors: List<String>)

private sealed interface Located {
    class At(val start: Int, val end: Int) : Located
    object Many : Located
    object None : Located
}

/** Where [search] is in [text]: exactly, or failing that with differences in trailing spaces ignored. */
private fun locate(text: String, search: String): Located {
    val first = text.indexOf(search)
    if (first >= 0) {
        return if (text.indexOf(search, first + 1) >= 0) Located.Many else Located.At(first, first + search.length)
    }
    val pattern = search.split('\n').joinToString("[ \\t]*\\n") { Regex.escape(it.trimEnd()) } + "[ \\t]*"
    val found = Regex(pattern).findAll(text).take(2).toList()
    return when (found.size) {
        0 -> Located.None
        1 -> Located.At(found[0].range.first, found[0].range.last + 1)
        else -> Located.Many
    }
}

/**
 * Applies the blocks one after the other to [original]. All or nothing: if any block cannot be applied, the text is
 * returned unchanged along with the reasons. A block must match exactly one place. An empty SEARCH is only accepted
 * when the file is empty, so a model can never wipe out a file that has content.
 */
fun applyFileEdits(original: String, edits: List<FileEdit>): ApplyOutcome {
    var text = original
    val errors = ArrayList<String>()
    for ((idx, e) in edits.withIndex()) {
        val label = "block ${idx + 1}"
        if (e.searchEmpty) {
            if (text.isEmpty()) text = e.replace else errors += "$label: an empty SEARCH only works for a new or empty file"
            continue
        }
        val at = when (val found = locate(text, e.search)) {
            is Located.At -> found
            Located.Many -> {
                errors += "$label: the SEARCH text matches more than one place; include more surrounding lines"
                continue
            }
            Located.None -> {
                val first = e.search.lineSequence().first().trim().take(60)
                errors += "$label: the SEARCH text was not found (it starts with: $first)"
                continue
            }
        }
        var to = at.end
        // Deleting whole lines should not leave an empty line behind.
        if (e.replaceEmpty && to < text.length && text[to] == '\n' && (at.start == 0 || text[at.start - 1] == '\n')) to++
        text = text.substring(0, at.start) + e.replace + text.substring(to)
    }
    return if (errors.isEmpty()) ApplyOutcome(text, emptyList()) else ApplyOutcome(original, errors)
}

// ---- Showing a change ----

enum class DiffKind { SAME, ADD, DEL, SKIP }

data class DiffLine(val kind: DiffKind, val text: String)

private const val MAX_DIFF_CELLS = 1_000_000L

private fun middleDiff(a: List<String>, b: List<String>): List<DiffLine> {
    if (a.isEmpty()) return b.map { DiffLine(DiffKind.ADD, it) }
    if (b.isEmpty()) return a.map { DiffLine(DiffKind.DEL, it) }
    if (a.size.toLong() * b.size > MAX_DIFF_CELLS) {
        return a.map { DiffLine(DiffKind.DEL, it) } + b.map { DiffLine(DiffKind.ADD, it) }
    }
    val n = a.size
    val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val out = ArrayList<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                out += DiffLine(DiffKind.SAME, a[i])
                i++
                j++
            }

            dp[i + 1][j] >= dp[i][j + 1] -> {
                out += DiffLine(DiffKind.DEL, a[i])
                i++
            }

            else -> {
                out += DiffLine(DiffKind.ADD, b[j])
                j++
            }
        }
    }
    while (i < n) out += DiffLine(DiffKind.DEL, a[i++])
    while (j < m) out += DiffLine(DiffKind.ADD, b[j++])
    return out
}

/** A line by line comparison of two texts. Lines that are the same at the start and end are matched first, which keeps it quick. */
fun lineDiff(old: String, new: String): List<DiffLine> {
    val a = if (old.isEmpty()) emptyList() else old.split('\n')
    val b = if (new.isEmpty()) emptyList() else new.split('\n')
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
    val out = ArrayList<DiffLine>()
    for (i in 0 until pre) out += DiffLine(DiffKind.SAME, a[i])
    out += middleDiff(a.subList(pre, a.size - suf), b.subList(pre, b.size - suf))
    for (i in a.size - suf until a.size) out += DiffLine(DiffKind.SAME, a[i])
    return out
}

/** Keeps the changed lines and [context] lines around each, and replaces the rest with a line saying how many were left out. */
fun foldDiff(lines: List<DiffLine>, context: Int = 2): List<DiffLine> {
    val keep = BooleanArray(lines.size)
    lines.forEachIndexed { i, l ->
        if (l.kind == DiffKind.ADD || l.kind == DiffKind.DEL) {
            for (k in maxOf(0, i - context)..minOf(lines.lastIndex, i + context)) keep[k] = true
        }
    }
    val out = ArrayList<DiffLine>()
    var skipped = 0
    fun flush() {
        if (skipped > 0) {
            out += DiffLine(DiffKind.SKIP, "\u2026 $skipped unchanged line${if (skipped == 1) "" else "s"}")
            skipped = 0
        }
    }
    lines.forEachIndexed { i, l ->
        if (keep[i]) {
            flush()
            out += l
        } else {
            skipped++
        }
    }
    flush()
    return out
}

// ---- Asking the model ----

/** A file to show the model. */
class PromptFile(val path: String, val text: String, val active: Boolean = false)

/** Files are included until this many characters have been used; larger ones are left out and named. */
const val EDIT_PROMPT_BUDGET = 60_000

private const val MAX_LISTED_FILES = 200

val EDIT_SYSTEM_PROMPT: String = listOf(
    "You edit the user's project files. Reply with a short explanation, then one edit block per change.",
    "",
    "Edit block format (exactly):",
    "path/to/file.ext",
    "<<<<<<< SEARCH",
    "exact lines to replace, copied character for character from the file",
    "=======",
    "the new lines",
    ">>>>>>> REPLACE",
    "",
    "Rules:",
    "- The path line comes right before <<<<<<< SEARCH and must be one of the listed workspace files, or a new file.",
    "- SEARCH must match the current text exactly once, including indentation. Add a few unchanged neighbouring lines if needed to make it unique.",
    "- To create a new file, leave SEARCH empty (nothing between the markers) and put the whole file in REPLACE.",
    "- To delete lines, leave REPLACE empty.",
    "- Use several blocks for several changes. Do not repeat parts of a file that stay the same.",
    "- Never use a path that starts with / or contains ..",
    "- If nothing needs to change, or the user only asked a question, answer in plain text with no edit blocks.",
).joinToString("\n")

/** The user's message for the model: the request, the list of workspace files and the current text of [files]. */
fun buildEditPrompt(request: String, files: List<PromptFile>, workspaceFiles: List<String>): String = buildString {
    append("Request:\n").append(request.trim()).append("\n\n")
    append("Workspace files (").append(workspaceFiles.size).append("):\n")
    for (f in workspaceFiles.take(MAX_LISTED_FILES)) append(f).append('\n')
    if (workspaceFiles.size > MAX_LISTED_FILES) {
        append("(and ").append(workspaceFiles.size - MAX_LISTED_FILES).append(" more)\n")
    }
    append('\n')
    if (files.isEmpty()) {
        append("No file is open in the editor.\n")
    } else {
        append("Current text of the files you can edit (includes typing that is not saved yet):\n")
        var left = EDIT_PROMPT_BUDGET
        for (f in files) {
            if (f.text.length > left) {
                append("=== ").append(f.path).append(" === (left out: too large to include)\n")
                continue
            }
            left -= f.text.length
            append("=== ").append(f.path)
            if (f.active) append(" (the file the user is looking at)")
            append(" ===\n")
            append(f.text)
            if (!f.text.endsWith("\n")) append('\n')
            append("=== end of ").append(f.path).append(" ===\n")
        }
    }
}
