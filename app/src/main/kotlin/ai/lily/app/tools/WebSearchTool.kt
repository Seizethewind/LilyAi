package ai.lily.app.tools

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest

/** Web search through the router (selected by RouterConfig.webSearchModel). See docs/router.md. */
class WebSearchTool(
    private val gateway: ModelGateway,
    private val model: String,
) : Tool {
    override val name = "web_search"

    override suspend fun execute(args: Map<String, String>): String {
        val query = args["query"] ?: error("query is required")
        return gateway.complete(
            ModelRequest(
                prompt = "Search the web for: $query\nAnswer concisely and list the source URLs.",
                system = "Use web search. Cite sources as URLs.",
                model = model,
            )
        ).text
    }
}
