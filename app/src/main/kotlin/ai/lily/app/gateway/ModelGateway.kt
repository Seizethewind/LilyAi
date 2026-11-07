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
    /** Pictures sent with the prompt, for example a screenshot. Only a model that can see images makes use of them. */
    val images: List<ModelImage> = emptyList(),
)

/** One picture as base64 text, with its type, for example image/jpeg. */
data class ModelImage(val mime: String, val base64: String)

data class ModelResponse(
    val text: String,
    /** Router X-Routed-Via header, e.g. groq/llama-3.3-70b. */
    val routedVia: String? = null,
)
