package ai.lily.app.lib.editor

import kotlin.math.abs

/**
 * Undo and redo for one open file. It keeps whole snapshots of the text, so it caps how many it holds
 * and how much text they add up to. Typing a few characters in a row counts as one step.
 */
class UndoHistory(initialText: String, selStart: Int = 0, selEnd: Int = 0) {

    class Snapshot(val text: String, val selStart: Int, val selEnd: Int)

    private val past = ArrayDeque<Snapshot>()
    private val future = ArrayDeque<Snapshot>()
    private var present = Snapshot(initialText, selStart, selEnd)
    private var lastChangeAt = 0L
    private var lastWasSmall = false

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /**
     * Notes that the editor now holds [text]. A change to the selection only updates the cursor.
     * [boundary] forces a new undo step (used for paste, replace, duplicate line and similar).
     */
    fun record(text: String, selStart: Int, selEnd: Int, now: Long, boundary: Boolean = false) {
        if (text == present.text) {
            present = Snapshot(text, selStart, selEnd)
            return
        }
        val small = abs(text.length - present.text.length) <= 1
        val merge = !boundary && small && lastWasSmall && now - lastChangeAt < MERGE_MS && past.isNotEmpty()
        if (!merge) past.addLast(present)
        future.clear()
        present = Snapshot(text, selStart, selEnd)
        lastChangeAt = now
        lastWasSmall = small
        trim()
    }

    /** Steps back. Returns what the editor should now show, or null if there is nothing to undo. */
    fun undo(): Snapshot? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(present)
        present = previous
        lastWasSmall = false
        return previous
    }

    fun redo(): Snapshot? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(present)
        present = next
        lastWasSmall = false
        return next
    }

    private fun trim() {
        var total = 0L
        for (s in past) total += s.text.length
        for (s in future) total += s.text.length
        while (past.isNotEmpty() && (past.size > MAX_ENTRIES || total > MAX_CHARS)) {
            total -= past.removeFirst().text.length
        }
    }

    private companion object {
        const val MERGE_MS = 1_000L
        const val MAX_ENTRIES = 200
        const val MAX_CHARS = 6_000_000L
    }
}
