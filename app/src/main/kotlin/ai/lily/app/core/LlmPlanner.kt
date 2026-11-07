package ai.lily.app.core

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import org.json.JSONArray
import java.util.UUID

/**
 * Asks the router for a JSON task list. Falls back to one GeneralWorker task if parsing fails.
 * [teamRole] says Lily has a TEAM worker; only then is the planner told it exists, so it never plans a task nobody can run.
 */
class LlmPlanner(private val gateway: ModelGateway, private val teamRole: Boolean = false) : Planner {

    override suspend fun plan(request: String, context: Context): List<Task> {
        val text = gateway.complete(ModelRequest(prompt = request, system = systemPrompt(teamRole), model = "auto:smart")).text
        return parse(text) ?: listOf(Task(UUID.randomUUID().toString(), request, WorkerType.GENERAL))
    }

    private fun parse(text: String): List<Task>? = runCatching {
        val arr = JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Task(
                id = UUID.randomUUID().toString(),
                description = o.getString("description"),
                worker = runCatching { WorkerType.valueOf(o.getString("worker").uppercase()) }
                    .getOrDefault(WorkerType.GENERAL),
            )
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    companion object {
        internal fun systemPrompt(teamRole: Boolean): String = "You are a planner. Break the request into small tasks. " +
            "Reply with ONLY a JSON array. Each item has description and worker. " +
            "worker is one of CODING, RESEARCH, BUILD, GIT, FILE, GENERAL, TEST${if (teamRole) ", TEAM" else ""}. " +
            "Use BUILD for anything to run in a shell or terminal (Termux), and for builds. " +
            "Use TEST to review code, hunt for bugs or write test cases, and to check a web page: " +
            "put the full address (https://...) in that task's description, or say the current website to check the open one. " +
            "To have a page used, not only looked at, say so in the task (for example \"click through the signup form at https://...\")." +
            if (teamRole) {
                " Use TEAM only to create, change or dismiss the person's workers (for example \"make 5 testers\"); " +
                    "put the whole wish in one TEAM task."
            } else {
                ""
            }
    }
}
