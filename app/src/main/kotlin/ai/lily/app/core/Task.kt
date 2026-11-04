package ai.lily.app.core

/** TEAM is Lily's own role: the worker that creates and dismisses the other workers. People do not create TEAM workers. */
enum class WorkerType { CODING, RESEARCH, BUILD, GIT, FILE, GENERAL, TEST, TEAM }

data class Task(
    val id: String,
    val description: String,
    val worker: WorkerType = WorkerType.GENERAL,
)

data class TaskResult(val taskId: String, val output: String, val success: Boolean)
