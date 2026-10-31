package ai.lily.app.gateway

/** Router connection settings. The key comes from encrypted app settings, never from source. */
data class RouterConfig(
    val baseUrl: String = "http://127.0.0.1:3001/v1",
    val apiKey: String = "",
    val defaultModel: String = "auto",
    /** Router model/profile that has web search enabled. Create it in the router dashboard or change it here. */
    val webSearchModel: String = "auto:search",
)
