package ai.lily.app.workers

import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.lib.editor.EDIT_SYSTEM_PROMPT
import ai.lily.app.lib.editor.FileEdit
import ai.lily.app.lib.editor.PromptFile
import ai.lily.app.lib.editor.applyFileEdits
import ai.lily.app.lib.editor.buildEditPrompt
import ai.lily.app.lib.editor.isSafePath
import ai.lily.app.lib.editor.parseReply
import ai.lily.app.tools.EditorBridge
import ai.lily.app.tools.ProposedEdit

/**
 * The way the coding and file workers change the files in the editor.
 *
 * It shows the model the open files (with typing that is not saved yet) and asks for edit blocks. Each file's blocks are
 * applied all or nothing against the text the person sees. If some do not fit, the model gets one chance to correct them.
 * What worked is handed to the editor as a proposal; nothing is written until the person accepts it.
 */
class EditPipeline(
    private val gateway: ModelGateway,
    private val editor: EditorBridge,
    private val model: String,
) {
    suspend fun run(task: Task, extraSystem: String = ""): TaskResult {
        val open = editor.openFiles()
        val listing = editor.listFiles()
        val texts = LinkedHashMap<String, String>()
        for (f in open.sortedByDescending { it.active }) texts[f.path] = f.text
        for (p in listing) {
            if (p !in texts && mentions(task.description, p)) editor.readFile(p)?.let { texts[p] = it }
        }
        val activePath = open.firstOrNull { it.active }?.path
        val system = EDIT_SYSTEM_PROMPT + extraSystem
        val shown = texts.map { (p, t) -> PromptFile(p, t, active = p == activePath) }

        val first = gateway.complete(ModelRequest(buildEditPrompt(task.description, shown, listing), system, model))
        val parsed = parseReply(first.text)
        if (parsed.edits.isEmpty()) return TaskResult(task.id, first.text.trim(), true)

        val results = LinkedHashMap<String, String>()
        val created = HashSet<String>()
        val counts = HashMap<String, Int>()
        var failures: Map<String, List<String>> = applyAll(parsed.edits, texts, results, created, counts)

        if (failures.isNotEmpty()) {
            val retryFiles = failures.keys.map { PromptFile(it, texts[it] ?: "") }
            val note = failures.entries.joinToString("\n") { (p, errs) -> "$p: ${errs.joinToString("; ")}" }
            val prompt = buildEditPrompt(task.description, retryFiles, listing) +
                "\nSome edits could not be applied:\n$note\nResend ONLY corrected edit blocks for these files.\n"
            val second = gateway.complete(ModelRequest(prompt, system, model))
            val wanted = failures.keys.toSet()
            val retryEdits = parseReply(second.text).edits.filter { it.path in wanted }
            val retried = retryEdits.map { it.path }.toSet()
            failures = failures.filterKeys { it !in retried } + applyAll(retryEdits, texts, results, created, counts)
        }

        val proposals = results.mapNotNull { (path, text) ->
            val isNew = path in created
            if (!isNew && text == (texts[path] ?: "")) {
                null
            } else {
                ProposedEdit(path, text, summary = "${counts[path] ?: 1} change(s)", isNew = isNew)
            }
        }

        val out = StringBuilder()
        if (parsed.prose.isNotBlank()) out.append(parsed.prose.trim()).append("\n\n")
        if (proposals.isNotEmpty()) {
            out.append(editor.propose(proposals))
        } else if (failures.isEmpty()) {
            out.append("No changes were needed.")
        }
        if (failures.isNotEmpty()) {
            out.append("\n\nCould not apply:\n")
            for ((p, errs) in failures) out.append("- ").append(p).append(": ").append(errs.joinToString("; ")).append('\n')
        }
        return TaskResult(task.id, out.toString().trim(), failures.isEmpty())
    }

    /** Applies each file's blocks all or nothing. Returns the files that failed, with the reasons. */
    private suspend fun applyAll(
        edits: List<FileEdit>,
        texts: MutableMap<String, String>,
        results: MutableMap<String, String>,
        created: MutableSet<String>,
        counts: MutableMap<String, Int>,
    ): Map<String, List<String>> {
        val failures = LinkedHashMap<String, List<String>>()
        for ((path, group) in edits.groupBy { it.path }) {
            if (!isSafePath(path)) {
                failures[path] = listOf("that path is not allowed")
                continue
            }
            val existing = texts[path] ?: editor.readFile(path)
            val base: String
            val isNew: Boolean
            if (existing != null) {
                base = existing
                isNew = false
                texts[path] = existing
            } else if (group.all { it.searchEmpty }) {
                base = ""
                isNew = true
            } else {
                failures[path] = listOf("the file does not exist")
                continue
            }
            val outcome = applyFileEdits(base, group)
            if (outcome.errors.isNotEmpty()) {
                failures[path] = outcome.errors
                continue
            }
            results[path] = outcome.text
            counts[path] = group.size
            if (isNew) created += path
        }
        return failures
    }

    private fun mentions(description: String, path: String): Boolean =
        description.contains(path, ignoreCase = true) ||
            description.contains(path.substringAfterLast('/'), ignoreCase = true)
}
