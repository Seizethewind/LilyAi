package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.research.ResearchRunner
import ai.lily.app.tools.ToolSystem
import kotlin.coroutines.cancellation.CancellationException

/**
 * Web research. With a [research] runner it searches, reads pages, saves them as sources and writes a cited report (phase 11,
 * `docs/research-mode.md`). Without one it uses the web_search tool first and falls back to a plain model call, as before.
 */
class ResearchWorker(
    gateway: ModelGateway,
    tools: ToolSystem,
    private val research: ResearchRunner? = null,
) : Worker(WorkerType.RESEARCH, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        if (research != null) {
            return try {
                val report = research.run(task.description)
                TaskResult(task.id, report.toText(), report.answer != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TaskResult(task.id, "Research failed: ${e.message ?: e.javaClass.simpleName}", false)
            }
        }
        val search = tools.get("web_search")
        val text = search?.execute(mapOf("query" to task.description))
            ?: gateway.complete(ModelRequest(task.description, model = WorkerModels.forWorker(type))).text
        return TaskResult(task.id, text, true)
    }
}
