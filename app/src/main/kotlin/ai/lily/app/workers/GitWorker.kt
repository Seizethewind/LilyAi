package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.tools.ToolSystem

/**
 * Git operations. When the Git control plugin (Termux) is on, a git task is run through its git_* tools, the same ones the
 * Git dialog uses, so the person still approves each command on screen. [repo] is the folder set in that dialog; a folder
 * named in the task wins. Without the plugin the task is answered by the model, as before. TODO: GitHub API (pull requests, issues).
 */
class GitWorker(gateway: ModelGateway, tools: ToolSystem, private val repo: () -> String = { "" }) :
    Worker(WorkerType.GIT, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        val model = WorkerModels.forWorker(type)
        if (tools.get("git_status") != null) {
            val reply = gateway.complete(ModelRequest(task.description, system = CALL_ONLY, model = model)).text
            val call = parseGitCall(reply)
            if (call != null) {
                val (name, given) = call
                val tool = tools.get(name)
                    ?: return TaskResult(task.id, "error: the tool '$name' is not available; turn on Git control in Plugins", false)
                val folder = given["repo"] ?: repo().trim()
                if (folder.isEmpty()) {
                    return TaskResult(task.id, "error: no repo folder; set one in Git control or name it in the task", false)
                }
                val args = given + ("repo" to folder)
                val out = tool.execute(args)
                val shown = args.entries.joinToString(" ") { "${it.key}=${it.value}" }
                return TaskResult(task.id, "$ $name $shown\n$out", out.startsWith("exit=0"))
            }
        }
        val r = gateway.complete(ModelRequest(task.description, model = model))
        return TaskResult(task.id, r.text, true)
    }

    private companion object {
        const val CALL_ONLY = "You run git for the person. Reply with ONLY these lines and nothing else:\n" +
            "tool: one of git_status, git_diff, git_log, git_pull, git_add (stages everything), git_commit, git_push\n" +
            "repo: the repo folder, only if the task names one\n" +
            "path: one file, only for git_diff and only if the task names one\n" +
            "message: a one-line commit message, only for git_commit\n" +
            "If the task is not one of these git actions, reply NONE."
    }
}

private val GIT_PARAMS = mapOf(
    "git_status" to setOf("repo"),
    "git_diff" to setOf("repo", "path"),
    "git_log" to setOf("repo"),
    "git_pull" to setOf("repo"),
    "git_add" to setOf("repo"),
    "git_commit" to setOf("repo", "message"),
    "git_push" to setOf("repo"),
)

/**
 * Reads the model's `key: value` lines into a tool name and its arguments. Null when it asked for no git tool (NONE, prose,
 * a tool that does not exist). Arguments a tool does not take are dropped. shortcut: one-line values only, extend if a commit message needs several lines.
 */
internal fun parseGitCall(text: String): Pair<String, Map<String, String>>? {
    val fields = text.trim().trim('`').lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
    }.toMap()
    val tool = fields["tool"]?.lowercase() ?: return null
    val allowed = GIT_PARAMS[tool] ?: return null
    return tool to fields.filter { (k, v) -> k in allowed && v.isNotEmpty() }
}
