package ai.lily.app.lib.editor

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** One match in a file. [line] and [column] start at 1. [preview] is the line (trimmed, and cut if very long) and [previewStart] is where the match begins inside it. */
data class FileMatch(
    val line: Int,
    val column: Int,
    val length: Int,
    val preview: String,
    val previewStart: Int,
)

data class FileHits(val path: String, val hits: List<FileMatch>)

data class ProjectSearchResult(
    val files: List<FileHits> = emptyList(),
    val hitCount: Int = 0,
    val filesSearched: Int = 0,
    /** True when the search stopped at [MAX_SEARCH_HITS] matches. */
    val truncated: Boolean = false,
)

/** A search stops after this many matches in total, so a very common word does not flood the screen. */
const val MAX_SEARCH_HITS = 500

/** At most this many files are looked at in one search. */
const val MAX_SEARCH_FILES = 3_000

private const val MAX_SEARCH_FILE_BYTES = 1_000_000L
private const val MAX_HITS_PER_FILE = 100
private const val PREVIEW_WIDTH = 160
private const val PREVIEW_LEAD = 40

// Folders full of generated or third party files that nobody wants in their results.
val SEARCH_SKIPPED_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea")

private fun looksBinary(text: String): Boolean {
    val end = minOf(text.length, 2_000)
    for (i in 0 until end) if (text[i] == '\u0000') return true
    return false
}

/** Every match of [regex] in [text] with its line, column and a short preview, at most [maxHits] of them. */
fun searchText(text: String, regex: Regex, maxHits: Int = MAX_HITS_PER_FILE): List<FileMatch> {
    val out = ArrayList<FileMatch>()
    var line = 1
    var lineStart = 0
    var scanned = 0
    for (m in regex.findAll(text)) {
        if (m.value.isEmpty()) continue
        val start = m.range.first
        // Count the line breaks between the last match and this one.
        while (true) {
            val nl = text.indexOf('\n', scanned)
            if (nl < 0 || nl >= start) break
            line++
            lineStart = nl + 1
            scanned = nl + 1
        }
        var lineEnd = text.indexOf('\n', start)
        if (lineEnd < 0) lineEnd = text.length
        var from = lineStart
        while (from < start && (text[from] == ' ' || text[from] == '\t')) from++
        var to = lineEnd
        if (to - from > PREVIEW_WIDTH) {
            from = maxOf(from, start - PREVIEW_LEAD)
            to = minOf(lineEnd, from + PREVIEW_WIDTH)
        }
        val preview = text.substring(from, to).trimEnd('\r')
        val previewStart = start - from
        val length = minOf(m.range.last + 1, lineEnd) - start
        out += FileMatch(line, start - lineStart + 1, length, preview, previewStart)
        if (out.size >= maxHits) break
    }
    return out
}

/**
 * Searches the files under [root]. Folders like .git, node_modules and build are left out, files over 1 MB and binary
 * files are skipped, and [overrides] holds the text of files with unsaved typing (by path relative to [root]), which is
 * searched instead of what is on disk. Throws IllegalArgumentException for an invalid regex.
 * Call it from a background dispatcher; it checks for cancellation between files.
 */
suspend fun searchFiles(
    root: File,
    options: FindOptions,
    overrides: Map<String, String> = emptyMap(),
): ProjectSearchResult {
    if (options.query.isEmpty()) return ProjectSearchResult()
    val regex = buildFindRegex(options)
    val base = root.canonicalFile
    val found = ArrayList<FileHits>()
    var total = 0
    var searched = 0
    var truncated = false
    val files = base.walkTopDown()
        .onEnter { it == base || it.name !in SEARCH_SKIPPED_DIRS }
        .filter { it.isFile }
        .take(MAX_SEARCH_FILES)
    for (file in files) {
        currentCoroutineContext().ensureActive()
        val rel = file.relativeTo(base).path
        val text: String = overrides[rel] ?: run {
            if (file.length() > MAX_SEARCH_FILE_BYTES) return@run null
            try {
                file.readText()
            } catch (e: IOException) {
                null
            }
        } ?: continue
        searched++
        if (looksBinary(text)) continue
        val hits = searchText(text, regex, minOf(MAX_HITS_PER_FILE, MAX_SEARCH_HITS - total))
        if (hits.isEmpty()) continue
        found += FileHits(rel, hits)
        total += hits.size
        if (total >= MAX_SEARCH_HITS) {
            truncated = true
            break
        }
    }
    return ProjectSearchResult(found, total, searched, truncated)
}
