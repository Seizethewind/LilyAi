package ai.lily.app.core

import ai.lily.app.workers.Worker

/** Assigns tasks to workers. New workers register here; the core stays unchanged. */
class WorkerManager {
    private val workers = mutableMapOf<WorkerType, Worker>()

    fun register(worker: Worker) {
        workers[worker.type] = worker
    }

    suspend fun assign(task: Task, context: Context): TaskResult {
        val worker = workers[task.worker] ?: workers[WorkerType.GENERAL]
            ?: return TaskResult(task.id, "No worker for ${task.worker}", false)
        return runCatching { worker.run(task, context) }
            .getOrElse { TaskResult(task.id, "Worker failed: ${it.message}", false) }
    }
}
