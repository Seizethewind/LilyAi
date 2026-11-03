package ai.lily.app.settings

import ai.lily.app.gateway.RouterConfig
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig

/** Everything the user configures in the app. Null means not configured. */
data class AppSettings(
    val router: RouterConfig = RouterConfig(),
    val journalDb: TursoConfig? = null,
    val factsDb: TursoConfig? = null,
    val pandastack: PandaStackConfig? = null,
    /** A third database for the knowledge layer. Never the same as [factsDb] or [journalDb]. */
    val knowledgeDb: TursoConfig? = null,
    /** The embedding model and the router(s) that serve it, for meaning search. Null: keyword search only. */
    val embedding: EmbeddingSettings? = null,
    /** The person's own commands for verifying in the sandbox, one check each. Empty: the checks are detected from the project. */
    val verifyChecks: List<String> = emptyList(),
    /** Whether research tasks use research mode (reads web pages and saves them in the knowledge database). Off until chosen. */
    val researchMode: Boolean = false,
)

/** Reads and writes the list of check commands as one line each. */
object CheckCommands {
    const val MAX_COMMANDS = 20
    const val MAX_LENGTH = 500

    /** One command per non-blank line, trimmed, each cut at [MAX_LENGTH], at most [MAX_COMMANDS]. */
    fun parse(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { it.take(MAX_LENGTH) }.take(MAX_COMMANDS)

    fun format(commands: List<String>): String = commands.joinToString("\n")
}
