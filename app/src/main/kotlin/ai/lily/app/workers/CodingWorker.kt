package ai.lily.app.workers

import ai.lily.app.codeindex.WorkspaceIndex
import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.tools.EditorBridge
import ai.lily.app.tools.ToolSystem

/**
 * Code analysis, editing, debugging. With an [editor] it reads the open files and proposes changes for the person to
 * review; without one it just answers. With a [workspace] index it also finds files beyond the open tabs. TODO: verify
 * changes in the sandbox after they are accepted.
 */
class CodingWorker(
    gateway: ModelGateway,
    tools: ToolSystem,
    private val editor: EditorBridge? = null,
    private val workspace: WorkspaceIndex? = null,
) : Worker(WorkerType.CODING, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        val model = WorkerModels.forWorker(type)
        if (editor != null) return EditPipeline(gateway, editor, model, workspace).run(task)
        val r = gateway.complete(ModelRequest(task.description, model = model))
        return TaskResult(task.id, r.text, true)
    }
}
