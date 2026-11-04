package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.tools.ToolSystem

/**
 * Builds, tests, error analysis and running shell commands. Real builds run in GitHub Actions. TODO: read workflow runs.
 * When a plugin provides a "terminal" tool (the optional Termux plugin) a shell task is run through it; the person
 * still approves each command on screen. Without that tool the task is answered by the model, as before.
 */
class BuildWorker(gateway: ModelGateway, tools: ToolSystem) :
    Worker(WorkerType.BUILD, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        val model = WorkerModels.forWorker(type)
        val terminal = tools.get("terminal")
        if (terminal != null) {
            val ask = gateway.complete(ModelRequest(task.description, system = SHELL_ONLY, model = model)).text
            // shortcut: one-line commands only, extend when a task needs a script.
            val command = ask.trim().trim('`').removePrefix("bash").trim()
            if (command.isNotEmpty() && command != "NONE" && '\n' !in command) {
                val out = terminal.execute(mapOf("command" to command))
                return TaskResult(task.id, "$ $command\n$out", !out.startsWith("error:") && !out.startsWith("denied:"))
            }
        }
        val r = gateway.complete(ModelRequest(task.description, model = model))
        return TaskResult(task.id, r.text, true)
    }

    private companion object {
        const val SHELL_ONLY = "Reply with ONLY the one shell command line that does this task: no explanation, no code " +
            "fence, no quotes around it. If the task is not something to run in a shell, reply NONE."
    }
}
