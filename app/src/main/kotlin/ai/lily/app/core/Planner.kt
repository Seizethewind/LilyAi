package ai.lily.app.core

/** Breaks a user request into tasks. */
interface Planner {
    suspend fun plan(request: String, context: Context): List<Task>
}
