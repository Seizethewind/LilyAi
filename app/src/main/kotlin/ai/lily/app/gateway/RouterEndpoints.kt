package ai.lily.app.gateway

/** How a router wants a caller to prove who it is. */
enum class EndpointAuth(val code: Char, val label: String) {
    Open('O', "No key needed"),
    ApiKey('K', "API key"),
    UnifiedKey('U', "Main API key only"),
    Session('S', "Dashboard login"),
    LocalOnly('L', "This machine only"),
    UrlToken('T', "Token in the address");

    companion object {
        fun fromCode(code: Char): EndpointAuth = entries.firstOrNull { it.code == code } ?: error("Unknown auth code '$code'")
    }
}

/**
 * One route a router serves. [path] starts at the server root (it already includes any /v1 or /api part) and keeps placeholders
 * such as `:id` or `*path` as the router writes them. [method] is `ANY` for an address the router rewrites for every method.
 */
data class RouterEndpoint(val method: String, val path: String, val auth: EndpointAuth, val group: String) {
    /** The address on [config]'s server. Placeholders stay in the text for the caller to fill in. */
    fun url(config: RouterConfig): String = config.origin + path

    /** True when every word of [query] appears in the method, path, group or auth label. An empty query matches everything. */
    fun matches(query: String): Boolean {
        val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val text = "$method $path $group ${auth.label}".lowercase()
        return words.all { text.contains(it) }
    }
}

/** Reads the endpoint tables written next to this file: `# Group` starts a group, every other line is `METHOD /path AUTH-CODE`. */
object EndpointTable {
    fun parse(table: String): List<RouterEndpoint> {
        var group = ""
        return table.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            if (line.startsWith("#")) {
                group = line.removePrefix("#").trim()
                null
            } else {
                val parts = line.split(' ')
                require(parts.size == 3 && parts[2].length == 1 && parts[1].startsWith("/")) { "Bad endpoint line: $line" }
                RouterEndpoint(parts[0], parts[1], EndpointAuth.fromCode(parts[2][0]), group)
            }
        }
    }
}

/** The two router programs the app knows. The kind picks which endpoint list applies and where the health check goes. */
enum class RouterKind(val id: String, val label: String, val healthPath: String) {
    FreeLlmApi("freellmapi", "FreeLLMAPI", "/livez"),
    NineRouter("9router", "9Router", "/api/health");

    /** Every route this router serves, grouped. Kept complete on purpose, including ones the app does not call yet. */
    val endpoints: List<RouterEndpoint>
        get() = when (this) {
            FreeLlmApi -> FreeLlmApiEndpoints.all
            NineRouter -> NineRouterEndpoints.all
        }

    companion object {
        /** An empty or unknown saved id (settings written before this field existed) gives [fallback]. */
        fun fromId(id: String?, fallback: RouterKind): RouterKind = entries.firstOrNull { it.id == id } ?: fallback
    }
}
