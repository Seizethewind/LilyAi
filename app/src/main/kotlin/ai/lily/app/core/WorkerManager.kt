package ai.lily.app.core

import ai.lily.app.workers.Worker
import kotlin.coroutines.cancellation.CancellationException

/**
 * Assigns tasks to workers. New workers register here; the core stays unchanged.
 * Several workers can share a role. Tasks for that role go to them in turn, and when one fails (for example a free
 * model is rate limited) the next one gets the task. A worker that answers "no" or "denied" is an answer, not a failure.
 */
class WorkerManager {
    private val workers = mutableMapOf<WorkerType, MutableList<Worker>>()
    private val turn = mutableMapOf<WorkerType, Int>()

    fun register(worker: Worker) {
        workers.getOrPut(worker.type) { mutableListOf() } += worker
    }

    suspend fun assign(task: Task, context: Context): TaskResult {
        val type = if (workers[task.worker].isNullOrEmpty()) WorkerType.GENERAL else task.worker
        val pool = workers[type].orEmpty()
        if (pool.isEmpty()) return TaskResult(task.id, "No worker for ${task.worker}", false)

        val start = (turn[type] ?: 0) % pool.size
        turn[type] = start + 1
        var last = "Worker failed"
        for (i in pool.indices) {
            val worker = pool[(start + i) % pool.size]
            try {
                return worker.run(task, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = "Worker failed: ${e.message}"
            }
        }
        return TaskResult(task.id, last, false)
    }
}
