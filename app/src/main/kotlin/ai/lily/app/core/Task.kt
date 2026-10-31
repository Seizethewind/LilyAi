package ai.lily.app.core

enum class WorkerType { CODING, RESEARCH, BUILD, GIT, FILE, GENERAL }

data class Task(
    val id: String,
    val description: String,
    val worker: WorkerType = WorkerType.GENERAL,
)

data class TaskResult(val taskId: String, val output: String, val success: Boolean)
