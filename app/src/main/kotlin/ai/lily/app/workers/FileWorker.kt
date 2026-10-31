package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.tools.ToolSystem

/** File and project operations. TODO: add a FileTool. */
class FileWorker(gateway: ModelGateway, tools: ToolSystem) :
    Worker(WorkerType.FILE, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        val r = gateway.complete(ModelRequest(task.description, model = WorkerModels.forWorker(type)))
        return TaskResult(task.id, r.text, true)
    }
}
