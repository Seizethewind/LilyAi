package ai.lily.app.settings

import ai.lily.app.gateway.EmbeddingEndpoint

/** One router that serves embeddings. [model] is only needed when this router names the model differently from the main one. */
data class EmbeddingServer(val baseUrl: String, val apiKey: String = "", val model: String = "")

/** What the settings screen made of the embedding fields: the settings, or the reason they cannot be used. */
data class EmbeddingFields(val settings: EmbeddingSettings?, val problem: String?)

/**
 * The embedding model and the router(s) that serve it.
 *
 * [model] is a real model id from the router's embedding list, never "auto" (the vector size could change between calls).
 * Vectors from different models cannot be compared, so every vector is stored with a tag, and only endpoints with that tag may
 * answer. [tag] defaults to the model id (with the dimensions, if set). [fallback] is a second router that stands in when the
 * first fails; it uses the same tag, so give it only when it serves the same model, even if it names that model differently.
 *
 * Changing the model or the tag later does not delete the old vectors; chunks simply count as not yet embedded for the new tag.
 */
data class EmbeddingSettings(
    val model: String,
    val tag: String = "",
    val dimensions: Int? = null,
    val primary: EmbeddingServer,
    val fallback: EmbeddingServer? = null,
) {
    val effectiveTag: String get() = tag.trim().ifEmpty { EmbeddingEndpoint.defaultTag(model, dimensions) }

    /** The endpoints in order of preference. Throws IllegalArgumentException with a readable message if one is not valid. */
    fun endpoints(): List<EmbeddingEndpoint> {
        val shared = effectiveTag
        val out = ArrayList<EmbeddingEndpoint>()
        out += EmbeddingEndpoint(
            name = "primary",
            baseUrl = primary.baseUrl.trim(),
            apiKey = primary.apiKey.trim(),
            model = primary.model.trim().ifEmpty { model.trim() },
            dimensions = dimensions,
            tag = shared,
        )
        fallback?.let {
            out += EmbeddingEndpoint(
                name = "fallback",
                baseUrl = it.baseUrl.trim(),
                apiKey = it.apiKey.trim(),
                model = it.model.trim().ifEmpty { model.trim() },
                dimensions = dimensions,
                tag = shared,
            )
        }
        return out
    }

    /** Why these settings cannot be used, or null if they can. */
    fun problem(): String? = try {
        endpoints()
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "The embedding settings are not valid."
    }

    companion object {
        /**
         * Builds the settings from what was typed. Nothing typed at all means no embedding (keyword search only). Anything typed
         * must be complete: a model and a router address, and an address for the fallback if any fallback field is filled.
         */
        fun fromFields(
            model: String,
            tag: String,
            dimensions: String,
            url: String,
            key: String,
            fallbackUrl: String,
            fallbackModel: String,
            fallbackKey: String,
        ): EmbeddingFields {
            val all = listOf(model, tag, dimensions, url, key, fallbackUrl, fallbackModel, fallbackKey).map { it.trim() }
            if (all.all { it.isEmpty() }) return EmbeddingFields(null, null)
            if (url.isBlank()) return EmbeddingFields(null, "Enter the embeddings router address, or clear every embedding field.")
            if (model.isBlank()) return EmbeddingFields(null, "Enter the embedding model id.")
            val dims = if (dimensions.isBlank()) {
                null
            } else {
                dimensions.trim().toIntOrNull() ?: return EmbeddingFields(null, "Dimensions must be a whole number.")
            }
            val hasFallback = listOf(fallbackUrl, fallbackModel, fallbackKey).any { it.isNotBlank() }
            if (hasFallback && fallbackUrl.isBlank()) return EmbeddingFields(null, "The fallback router needs an address.")
            val settings = EmbeddingSettings(
                model = model.trim(),
                tag = tag.trim(),
                dimensions = dims,
                primary = EmbeddingServer(url.trim(), key.trim()),
                fallback = if (hasFallback) EmbeddingServer(fallbackUrl.trim(), fallbackKey.trim(), fallbackModel.trim()) else null,
            )
            val problem = settings.problem()
            return if (problem != null) EmbeddingFields(null, problem) else EmbeddingFields(settings, null)
        }
    }
}
