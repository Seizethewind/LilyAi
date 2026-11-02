package ai.lily.app.lib.editor

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** A size for people: `512 B`, `1.5 KB`, `12.3 MB`, `1.2 GB`. */
fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / kb)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (kb * kb))
        else -> String.format(Locale.US, "%.1f GB", bytes / (kb * kb * kb))
    }
}

/**
 * Reads a file that is too big to open in the editor, without loading it.
 *
 * [buildIndex] reads the file once, in pieces, counting lines and remembering where every [every]th line starts (a few numbers per
 * thousand lines, so a file of millions of lines needs a few kilobytes). After that, [readLines] jumps to the nearest remembered
 * line and reads only what is asked for, and the search functions read the file in pieces again, one line at a time.
 *
 * - Lines end at a newline byte; a `\r` before it is dropped. UTF-8 never uses the newline byte inside a character, so this is
 *   safe for any UTF-8 text. Bytes that are not valid UTF-8 show as replacement characters.
 * - A line longer than [MAX_LINE_BYTES] is cut there and ends with a note of how many bytes were left out. Search only sees the
 *   part that is shown.
 * - Line numbers here start at 0.
 * - A file that ends with a newline does not have an extra empty line at the end. An empty file has no lines.
 *
 * Every read opens the file again, so reads and searches can run on several threads once the index is built. The file is not locked:
 * if something changes it after indexing, lines may be wrong until the index is built again.
 */
class LargeFileReader(private val file: File, private val every: Int = CHECKPOINT_EVERY) {
    init {
        require(every > 0) { "The checkpoint spacing must be positive." }
    }

    var lineCount: Int = 0
        private set
    var sizeBytes: Long = 0
        private set
    private var checkpoints = LongArray(0)
    private var ready = false

    /**
     * Reads the whole file once. [cancelled] is asked between pieces; if it says yes, this stops and returns false and the reader is
     * left as it was. [onProgress] gets the number of bytes read so far.
     */
    fun buildIndex(cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): Boolean {
        val points = ArrayList<Long>()
        points += 0L
        var newlines = 0
        var pos = 0L
        var last = -1
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(BUFFER)
            while (true) {
                if (cancelled()) return false
                val n = raf.read(buf)
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i] == NL) {
                        newlines++
                        if (newlines % every == 0) points += pos + i + 1
                    }
                }
                last = buf[n - 1].toInt()
                pos += n
                onProgress(pos)
            }
        }
        val total = when {
            pos == 0L -> 0
            last == NL.toInt() -> newlines
            else -> newlines + 1
        }
        val count = if (total == 0) 0 else (total - 1) / every + 1
        checkpoints = LongArray(count) { points[it] }
        lineCount = total
        sizeBytes = pos
        ready = true
        return true
    }

    /** Up to [count] lines starting at line [from]. Empty if [from] is past the end. */
    fun readLines(from: Int, count: Int): List<String> {
        check(ready) { "The index has not been built." }
        if (count <= 0 || from >= lineCount) return emptyList()
        val start = from.coerceAtLeast(0)
        val end = minOf(lineCount, start + count)
        return atLine(start) { cursor, first ->
            val out = ArrayList<String>(end - first)
            var line = first
            while (line < end) {
                out += cursor.nextLine() ?: break
                line++
            }
            out
        }
    }

    /**
     * The first line at or after [fromLine] that contains [query] (plain text), going on from the start of the file if there is none
     * after it. Null if no line has it, the query is empty, or [cancelled] said stop.
     */
    fun findNext(query: String, fromLine: Int, ignoreCase: Boolean = true, cancelled: () -> Boolean = { false }): Int? {
        check(ready) { "The index has not been built." }
        if (query.isEmpty() || lineCount == 0) return null
        val start = fromLine.coerceAtLeast(0)
        if (start >= lineCount) return scan(0, lineCount - 1, query, ignoreCase, wantLast = false, cancelled)
        return scan(start, lineCount - 1, query, ignoreCase, wantLast = false, cancelled)
            ?: if (start > 0) scan(0, start - 1, query, ignoreCase, wantLast = false, cancelled) else null
    }

    /**
     * The last line before [fromLine] that contains [query], going on from the end of the file if there is none before it. Null if
     * no line has it, the query is empty, or [cancelled] said stop.
     */
    fun findPrevious(query: String, fromLine: Int, ignoreCase: Boolean = true, cancelled: () -> Boolean = { false }): Int? {
        check(ready) { "The index has not been built." }
        if (query.isEmpty() || lineCount == 0) return null
        val before = minOf(fromLine, lineCount) - 1
        return lastMatch(0, before, query, ignoreCase, cancelled)
            ?: lastMatch(maxOf(before + 1, 0), lineCount - 1, query, ignoreCase, cancelled)
    }

    /** The last match in lines [from]..[to], looking at the file one block of lines at a time from the end. */
    private fun lastMatch(from: Int, to: Int, query: String, ignoreCase: Boolean, cancelled: () -> Boolean): Int? {
        if (from > to) return null
        var block = to / every
        while (block >= from / every) {
            if (cancelled()) return null
            val lo = maxOf(from, block * every)
            val hi = minOf(to, block * every + every - 1)
            scan(lo, hi, query, ignoreCase, wantLast = true, cancelled)?.let { return it }
            block--
        }
        return null
    }

    /** The first (or, with [wantLast], the last) line in [lo]..[hi] that contains [query]. */
    private fun scan(lo: Int, hi: Int, query: String, ignoreCase: Boolean, wantLast: Boolean, cancelled: () -> Boolean): Int? {
        if (lo > hi) return null
        return atLine(lo) { cursor, first ->
            var found: Int? = null
            var line = first
            var counter = 0
            while (line <= hi) {
                if ((++counter and 255) == 0 && cancelled()) return@atLine null
                val text = cursor.nextLine() ?: break
                if (text.contains(query, ignoreCase)) {
                    found = line
                    if (!wantLast) break
                }
                line++
            }
            found
        }
    }

    /** Opens the file with a cursor placed at the start of [line]. */
    private inline fun <T> atLine(line: Int, block: (LineCursor, Int) -> T): T {
        val b = line / every
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(checkpoints[b])
            val cursor = LineCursor(raf)
            var at = b * every
            while (at < line) {
                cursor.skipLine()
                at++
            }
            block(cursor, line)
        }
    }

    /** Reads lines one after another from a place in the file. */
    private class LineCursor(private val raf: RandomAccessFile) {
        private val buf = ByteArray(BUFFER)
        private var len = 0
        private var pos = 0
        private var line = ByteArray(256)

        private fun fill(): Boolean {
            len = raf.read(buf)
            pos = 0
            return len > 0
        }

        fun skipLine(): Boolean {
            var any = false
            while (true) {
                if (pos >= len && !fill()) return any
                any = true
                if (buf[pos++] == NL) return true
            }
        }

        /** The next line without its line end, or null at the end of the file. */
        fun nextLine(): String? {
            var size = 0
            var total = 0L
            var any = false
            while (true) {
                if (pos >= len && !fill()) {
                    if (!any) return null
                    break
                }
                any = true
                val b = buf[pos++]
                if (b == NL) break
                total++
                if (size < MAX_LINE_BYTES) {
                    if (size == line.size) line = line.copyOf(line.size * 2)
                    line[size++] = b
                }
            }
            if (total == size.toLong()) {
                if (size > 0 && line[size - 1] == CR) size--
                return String(line, 0, size, Charsets.UTF_8)
            }
            val shown = String(line, 0, size, Charsets.UTF_8).trimEnd('\uFFFD')
            return "$shown \u2026 (+${total - size} bytes)"
        }
    }

    companion object {
        const val CHECKPOINT_EVERY = 1_000
        const val MAX_LINE_BYTES = 20_000
        private const val BUFFER = 64 * 1024
        private const val NL: Byte = 10
        private const val CR: Byte = 13
    }
}

/**
 * A page of lines from a [LargeFileReader], [size] lines at a time. [firstLine] is the first line shown (from 0) and [lines] are the
 * lines on the page. A page never runs past the end of the file, so the last page is full when the file is long enough.
 */
class LargeFileWindow(private val reader: LargeFileReader, val size: Int = 200) {
    init {
        require(size > 0) { "A page needs at least one line." }
    }

    var firstLine: Int = 0
        private set
    var lines: List<String> = emptyList()
        private set

    val lineCount: Int get() = reader.lineCount

    /** Loads the page that starts at [first], moved back if it would run past the end. */
    fun load(first: Int) {
        firstLine = first.coerceIn(0, maxOf(0, lineCount - size))
        lines = reader.readLines(firstLine, size)
    }

    /** Loads a page that shows [line] a few lines from the top, so the lines just before it are visible too. */
    fun goToLine(line: Int) = load(line - CONTEXT_BEFORE)

    /** Moves by [delta] pages, each a little shorter than the page so the last lines of one page start the next. */
    fun page(delta: Int) = load(firstLine + delta * (size - size / 20).coerceAtLeast(1))

    companion object {
        const val CONTEXT_BEFORE = 3
    }
}
