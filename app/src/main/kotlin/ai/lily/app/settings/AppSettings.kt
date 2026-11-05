package ai.lily.app.settings

import ai.lily.app.gateway.EmbeddingEndpoint
import ai.lily.app.gateway.RouterConfig
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig
import ai.lily.app.workers.WorkerProfile

/** Everything the user configures in the app. Null means not configured. */
data class AppSettings(
    val router: RouterConfig = RouterConfig(),
    val journalDb: TursoConfig? = null,
    val factsDb: TursoConfig? = null,
    val pandastack: PandaStackConfig? = null,
    /** A third database for the knowledge layer. Never the same as [factsDb] or [journalDb]. */
    val knowledgeDb: TursoConfig? = null,
    /** The service that turns library text into meaning vectors. Without it, search stays keyword only. */
    val embedding: EmbeddingEndpoint? = null,
    /** Shell commands the sandbox check runs instead of the ones it detects. One command per entry; empty means detect. */
    val verifyCommands: List<String> = emptyList(),
    /** A second router, for spreading workers over two self-hosted routers. Null means only one is used. */
    val router2: RouterConfig? = null,
    /** The workers the person or Lily created. Empty means the built-in workers answer. */
    val workers: List<WorkerProfile> = emptyList(),
    /** Lily's own name, photo, rules, router and model. */
    val lily: LilyProfile = LilyProfile(),
) {
    /** The router a worker points at: 0 is the first, 1 the second. A missing second router falls back to the first. */
    fun routerAt(index: Int): RouterConfig = if (index == 1) router2 ?: router else router
}
