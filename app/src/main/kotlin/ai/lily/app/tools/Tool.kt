package ai.lily.app.tools

/** A capability provided to workers. */
interface Tool {
    val name: String
    suspend fun execute(args: Map<String, String>): String
}
