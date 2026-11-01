package ai.lily.app.editor

import ai.lily.app.tools.EditorBridge
import ai.lily.app.tools.OpenFile
import ai.lily.app.tools.ProposedEdit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A change Lily wants to make, waiting for the person to accept or reject it. [oldText] is the file as it was when she first looked. */
class Proposal(
    val path: String,
    val oldText: String,
    val newText: String,
    val isNew: Boolean,
    val summary: String,
)

/**
 * Connects the agent to the editor. The workers see the open files through [bridge] and hand back proposals, which wait in
 * [proposals] until the person accepts them. Nothing is written to a file here: an accepted change goes into the open tab as
 * one undo step, like typing, and is saved the normal way.
 */
class AgentSession(private val state: EditorState) {
    val proposals = mutableStateListOf<Proposal>()

    var busy by mutableStateOf(false)
        private set
    var reply by mutableStateOf<String?>(null)
    var reviewing by mutableStateOf(false)

    /** Set by the activity: sends a request to Lily and returns her answer. */
    var responder: (suspend (String) -> String)? = null

    private fun pendingFor(path: String): Proposal? = proposals.firstOrNull { it.path == path }

    /**
     * What the workers see. A file with a proposal waiting shows the proposed text, so a second task builds on the first
     * instead of overwriting it.
     */
    val bridge: EditorBridge = object : EditorBridge {
        override suspend fun openFiles(): List<OpenFile> = withContext(Dispatchers.Main.immediate) {
            state.tabs.mapIndexed { i, t ->
                OpenFile(t.path, pendingFor(t.path)?.newText ?: t.value.text, i == state.active, t.dirty)
            }
        }

        override suspend fun listFiles(): List<String> {
            val onDisk = state.listFiles()
            val added = withContext(Dispatchers.Main.immediate) { proposals.filter { it.isNew }.map { it.path } }
            return (onDisk + added).distinct().sorted()
        }

        override suspend fun readFile(path: String): String? = currentText(path)

        override suspend fun propose(edits: List<ProposedEdit>): String {
            val made = edits.map { e ->
                val pending = withContext(Dispatchers.Main.immediate) { pendingFor(e.path) }
                val old = pending?.oldText ?: if (e.isNew) "" else realText(e.path) ?: ""
                Proposal(e.path, old, e.newText, e.isNew || pending?.isNew == true, e.summary)
            }
            withContext(Dispatchers.Main.immediate) {
                for (p in made) {
                    proposals.removeAll { it.path == p.path }
                    proposals.add(p)
                }
            }
            return "Proposed changes to " + made.joinToString(", ") { it.path } +
                ". They are waiting for your review in the editor."
        }
    }

    /** The text of a file as the workers should see it. */
    private suspend fun currentText(path: String): String? {
        val pending = withContext(Dispatchers.Main.immediate) { pendingFor(path)?.newText }
        return pending ?: realText(path)
    }

    /** The text of a file as it is in the editor (unsaved typing included) or on disk. */
    private suspend fun realText(path: String): String? {
        val tabText = withContext(Dispatchers.Main.immediate) { state.tabs.firstOrNull { it.path == path }?.value?.text }
        return tabText ?: withContext(Dispatchers.IO) { readDisk(path) }
    }

    private fun readDisk(path: String): String? {
        val base = state.root.canonicalFile
        val f = File(base, path).canonicalFile
        if (!f.path.startsWith(base.path + File.separator) || !f.isFile || f.length() > MAX_FILE_BYTES) return null
        return runCatching { f.readText().replace("\r\n", "\n") }.getOrNull()
    }

    // ---- Asking ----

    suspend fun ask(request: String) {
        val respond = responder
        if (respond == null) {
            reply = "Lily is not connected."
            return
        }
        if (busy) return
        busy = true
        try {
            reply = respond(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reply = "Lily could not finish: ${e.message ?: e::class.java.simpleName}. Check the router settings."
        } finally {
            busy = false
        }
        if (proposals.isEmpty()) reviewing = false
    }

    // ---- Reviewing ----

    fun startReview() {
        reply = null
        reviewing = true
    }

    /** Puts a proposal into its tab as one undo step. The tab is not saved. */
    suspend fun accept(p: Proposal) {
        var tab = state.tabs.firstOrNull { it.path == p.path }
        if (tab == null) {
            if (p.isNew) state.create(p.path) else state.open(p.path)
            tab = state.tabs.firstOrNull { it.path == p.path }
        }
        if (tab == null) {
            state.message = "Could not open ${p.path}"
            return
        }
        if (tab.value.text != p.oldText) {
            proposals.remove(p)
            state.message = "${p.path} changed after Lily proposed this, so it was not applied. Ask again."
            finishIfEmpty()
            return
        }
        val now = System.currentTimeMillis()
        tab.value = TextFieldValue(p.newText, TextRange(0))
        tab.history.record(p.newText, 0, 0, now, boundary = true)
        tab.editVersion++
        tab.lastEditAt = now
        state.active = state.tabs.indexOf(tab)
        proposals.remove(p)
        state.message = "Applied Lily's changes to ${tab.name}. Undo takes them back. Save to keep them."
        finishIfEmpty()
    }

    fun reject(p: Proposal) {
        proposals.remove(p)
        finishIfEmpty()
    }

    suspend fun acceptAll() {
        for (p in proposals.toList()) accept(p)
    }

    fun rejectAll() {
        proposals.clear()
        finishIfEmpty()
    }

    private fun finishIfEmpty() {
        if (proposals.isEmpty()) reviewing = false
    }
}
