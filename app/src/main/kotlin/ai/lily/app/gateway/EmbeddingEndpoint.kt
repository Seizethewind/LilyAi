package ai.lily.app.gateway

/**
 * One OpenAI-compatible `/embeddings` service: the 9Router, the FreeLLMAPI router, or any other.
 *
 * [baseUrl] ends in `/v1` like [RouterConfig.baseUrl] (for example `http://host:20128/v1` for 9Router, `http://host:3001/v1` for
 * FreeLLMAPI). [apiKey] may be empty when the router does not require one.
 *
 * [model] must be a real model id from the router's embedding list, never `auto`: `auto` may pick a model with a different vector
 * size on the next call, and vectors from different models cannot be compared.
 *
 * [tag] is the name the app stores with each vector. Two endpoints with the same tag are treated as the same model, so one can
 * stand in for the other when it is down. Give two endpoints the same tag only when they really serve the same model.
 * [dimensions] is sent to the router only when set; only OpenAI `text-embedding-3-*` models honour it.
 */
data class EmbeddingEndpoint(
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String,
    val dimensions: Int? = null,
    val batchSize: Int = DEFAULT_BATCH,
    val tag: String = defaultTag(model, dimensions),
) {
    init {
        require(name.isNotBlank()) { "An embedding endpoint needs a name." }
        require(baseUrl.isNotBlank()) { "The embedding endpoint $name has no address." }
        require(model.isNotBlank()) { "The embedding endpoint $name has no model." }
        require(!isAuto(model)) { "Pin a real embedding model id for $name; \"auto\" can change the vector size between calls." }
        require(dimensions == null || dimensions > 0) { "Dimensions must be positive." }
        require(batchSize in 1..MAX_BATCH) { "The batch size must be between 1 and $MAX_BATCH." }
        require(tag.isNotBlank()) { "An embedding endpoint needs a tag." }
    }

    companion object {
        const val DEFAULT_BATCH = 32
        const val MAX_BATCH = 256

        fun defaultTag(model: String, dimensions: Int?): String =
            if (dimensions == null) model.trim() else "${model.trim()}@$dimensions"

        private fun isAuto(model: String): Boolean {
            val m = model.trim().lowercase()
            return m == "auto" || m.startsWith("auto:")
        }
    }
}
