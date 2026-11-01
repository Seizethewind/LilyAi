package ai.lily.app.tools

/** A file that is open in the editor. [text] includes typing that is not saved yet. */
data class OpenFile(val path: String, val text: String, val active: Boolean, val dirty: Boolean)

/** A change a worker wants to make. [newText] is the whole new text of the file. */
data class ProposedEdit(val path: String, val newText: String, val summary: String, val isNew: Boolean = false)

/**
 * How workers see and change the files in the editor. Workers never write files themselves: they hand over proposed
 * changes, and the person reviews and accepts them in the editor. Paths are relative to the workspace.
 */
interface EditorBridge {
    /** The open tabs, with their current text. */
    suspend fun openFiles(): List<OpenFile>

    /** Every file in the workspace. */
    suspend fun listFiles(): List<String>

    /** The current text of a file (unsaved typing if it is open), or null if it does not exist or is too big. */
    suspend fun readFile(path: String): String?

    /** Puts changes up for review. Returns a sentence saying what happened, for the worker's answer. */
    suspend fun propose(edits: List<ProposedEdit>): String
}
