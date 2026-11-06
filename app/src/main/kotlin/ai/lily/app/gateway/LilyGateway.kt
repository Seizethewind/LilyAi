package ai.lily.app.gateway

/**
 * Adds Lily's own rules to every model call that goes through it. The rules go AFTER the caller's system text, under a heading,
 * so a planner's "reply with only a JSON array" and a worker's own instructions stay first. [model], when not blank, replaces the
 * model the caller asked for; leave it blank for workers, which keep their own models. Blank [rules] and [model] change nothing.
 *
 * [rules] is asked for on every call, so rules that come from a file the person can edit (the workspace's AGENT.md) apply from the
 * very next answer. The String constructor is for fixed rules.
 */
class LilyGateway(
    private val inner: ModelGateway,
    private val rules: () -> String,
    private val model: String = "",
) : ModelGateway {
    constructor(inner: ModelGateway, rules: String, model: String = "") : this(inner, { rules }, model)

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val block = rules().trim().takeIf { it.isNotEmpty() }?.let { HEADING + it }
        val system = listOfNotNull(request.system?.takeIf { it.isNotBlank() }, block).joinToString("\n\n")
        return inner.complete(request.copy(system = system.ifBlank { null }, model = model.trim().ifBlank { request.model }))
    }

    companion object {
        const val HEADING = "Rules from the person you work for. Follow them in the wording and style of your reply. " +
            "If the reply must keep a fixed format, such as a JSON array, keep that format.\n\n"
    }
}
