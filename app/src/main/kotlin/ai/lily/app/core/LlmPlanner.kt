package ai.lily.app.core

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import org.json.JSONArray
import java.util.UUID

/** Asks the router for a JSON task list. Falls back to one GeneralWorker task if parsing fails. */
class LlmPlanner(private val gateway: ModelGateway) : Planner {

    override suspend fun plan(request: String, context: Context): List<Task> {
        val system = "You are a planner. Break the request into small tasks. " +
            "Reply with ONLY a JSON array. Each item has description and worker. " +
            "worker is one of CODING, RESEARCH, BUILD, GIT, FILE, GENERAL."
        val text = gateway.complete(ModelRequest(prompt = request, system = system, model = "auto:smart")).text
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
}
