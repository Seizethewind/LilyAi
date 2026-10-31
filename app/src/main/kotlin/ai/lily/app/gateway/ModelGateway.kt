package ai.lily.app.gateway

/** The only way workers talk to models. Provider selection happens in the router, not here. */
interface ModelGateway {
    suspend fun complete(request: ModelRequest): ModelResponse
}

data class ModelRequest(
    val prompt: String,
    val system: String? = null,
    /** Router selector: auto, auto:fast, auto:smart, auto:<profile> or a model id. Null uses the default. */
    val model: String? = null,
)

data class ModelResponse(
    val text: String,
    /** Router X-Routed-Via header, e.g. groq/llama-3.3-70b. */
    val routedVia: String? = null,
)
