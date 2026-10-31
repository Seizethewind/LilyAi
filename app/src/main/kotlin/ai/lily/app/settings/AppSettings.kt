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
)
