package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.tools.ToolSystem

/** Web research. Uses the web_search tool first; falls back to a plain model call. */
class ResearchWorker(gateway: ModelGateway, tools: ToolSystem) :
    Worker(WorkerType.RESEARCH, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        val search = tools.get("web_search")
        val text = search?.execute(mapOf("query" to task.description))
            ?: gateway.complete(ModelRequest(task.description, model = WorkerModels.forWorker(type))).text
        return TaskResult(task.id, text, true)
    }
}
