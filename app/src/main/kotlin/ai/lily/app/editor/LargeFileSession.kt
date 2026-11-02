package ai.lily.app.editor

import ai.lily.app.lib.editor.LargeFileReader
import ai.lily.app.lib.editor.LargeFileWindow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * A file over 1 MB that is being looked at, one page of lines at a time. It cannot be edited. [reader] must already have its index
 * built. All the line numbers shown to the person start at 1; inside, they start at 0.
 */
class LargeFileSession(
    val path: String,
    val sizeBytes: Long,
    private val reader: LargeFileReader,
    private val window: LargeFileWindow = LargeFileWindow(reader),
) {
    var firstLine by mutableStateOf(0)
        private set
    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    /** The line a search or "go to line" landed on (from 0), or null when the person just scrolled or paged. */
    var highlightLine by mutableStateOf<Int?>(null)
        private set
    var status by mutableStateOf<String?>(null)

    val lineCount: Int get() = reader.lineCount
    val name: String get() = path.substringAfterLast('/')

    private fun publish() {
        firstLine = window.firstLine
        lines = window.lines
    }

    /** Shows the page that starts at line [first] (from 0). */
    suspend fun load(first: Int) {
        withContext(Dispatchers.IO) { window.load(first) }
        highlightLine = null
        status = null
        publish()
    }

    suspend fun page(delta: Int) {
        withContext(Dispatchers.IO) { window.page(delta) }
        highlightLine = null
        status = null
        publish()
    }

    /** Shows line [number] (from 1) and marks it. A number past the end goes to the last line. */
    suspend fun goToLine(number: Int) {
        if (lineCount == 0) return
        val target = (number - 1).coerceIn(0, lineCount - 1)
        withContext(Dispatchers.IO) { window.goToLine(target) }
        highlightLine = target
        status = "Line ${target + 1}"
        publish()
    }

    /** Finds [query] after (or, going [forward] = false, before) the marked line, or the top of the page if none is marked. */
    suspend fun find(query: String, forward: Boolean) {
        if (query.isEmpty()) return
        status = "Searching\u2026"
        val hit = withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            val stop = { job?.isActive == false }
            if (forward) {
                reader.findNext(query, (highlightLine ?: (firstLine - 1)) + 1, cancelled = stop)
            } else {
                reader.findPrevious(query, highlightLine ?: firstLine, cancelled = stop)
            }
        }
        if (hit == null) {
            status = "Not found"
        } else {
            goToLine(hit + 1)
        }
    }
}
