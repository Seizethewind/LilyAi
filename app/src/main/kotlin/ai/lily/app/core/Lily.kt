package ai.lily.app.core

/** Main manager/orchestrator: User -> Lily -> Planner -> Worker -> Result. Logs to the Journal when memory is set. */
class Lily(
    private val planner: Planner,
    private val workerManager: WorkerManager,
    private val context: Context = Context(),
    private val memory: Memory? = null,
) {
    suspend fun handle(request: String): List<TaskResult> {
        log("request", request)
        return planner.plan(request, context).map { task ->
            workerManager.assign(task, context).also {
                log("result", "[${task.worker}] ${task.description}\n=> ${it.output.take(2000)}")
            }
        }
    }

    /** A memory outage must never break a task. */
    private suspend fun log(kind: String, content: String) {
        runCatching { memory?.journal?.append(kind, content, context.projectPath) }
    }
}
