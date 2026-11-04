package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.tools.ToolSystem

/** A worker that only asks its model, with whatever instructions its profile gives. Used for roles with no code of their own, such as testers. */
class ModelWorker(type: WorkerType, gateway: ModelGateway, tools: ToolSystem) : Worker(type, gateway, tools) {
    override suspend fun run(task: Task, context: Context): TaskResult =
        TaskResult(task.id, gateway.complete(ModelRequest(task.description, model = WorkerModels.forWorker(type))).text, true)
}
