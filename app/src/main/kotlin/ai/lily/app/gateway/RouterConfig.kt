package ai.lily.app.gateway

/** Router connection settings. The key comes from encrypted app settings, never from source. */
data class RouterConfig(
    val baseUrl: String = "http://127.0.0.1:3001/v1",
    val apiKey: String = "",
    val defaultModel: String = "auto",
    /** Router model/profile that has web search enabled. Create it in the router dashboard or change it here. */
    val webSearchModel: String = "auto:search",
    /** What the person calls this router. Empty means the app shows the [kind]. */
    val name: String = "",
    /** Which router program this is; it decides which endpoint list the Router config page shows. */
    val kind: RouterKind = RouterKind.FreeLlmApi,
) {
    /** The server address without a trailing /v1. Endpoint paths already carry their own /v1 or /api part, so they are added to this. */
    val origin: String get() = baseUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

    val title: String get() = name.ifBlank { kind.label }
}
