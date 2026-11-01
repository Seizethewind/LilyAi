package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.tools.EditorBridge
import ai.lily.app.tools.ToolSystem

/**
 * File and project operations. With an [editor] it can create files and change existing ones as proposals the person
 * reviews. It cannot delete, rename or move files yet.
 */
class FileWorker(
    gateway: ModelGateway,
    tools: ToolSystem,
    private val editor: EditorBridge? = null,
) : Worker(WorkerType.FILE, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        val model = WorkerModels.forWorker(type)
        if (editor != null) return EditPipeline(gateway, editor, model).run(task, FILE_NOTE)
        val r = gateway.complete(ModelRequest(task.description, model = model))
        return TaskResult(task.id, r.text, true)
    }

    private companion object {
        const val FILE_NOTE = "\n\nFor this task you are the file worker: you can create new files and change existing ones. " +
            "You cannot delete, rename or move files. If asked to, say so, and for a rename create the new file with the same content."
    }
}
