package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.tools.ToolSystem

/** Workers use the ModelGateway and ToolSystem only. They never call model APIs directly. */
abstract class Worker(
    val type: WorkerType,
    protected val gateway: ModelGateway,
    protected val tools: ToolSystem,
) {
    abstract suspend fun run(task: Task, context: Context): TaskResult
}
