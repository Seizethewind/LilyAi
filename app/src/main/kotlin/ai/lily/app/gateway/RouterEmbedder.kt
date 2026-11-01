package ai.lily.app.gateway

import ai.lily.app.memory.Embedder
import ai.lily.app.memory.EmbeddingBatch
import ai.lily.app.memory.EmbeddingException
import ai.lily.app.memory.VectorCodec
import ai.lily.app.net.Http
import ai.lily.app.net.HttpResponse
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

/** How a request is sent. The default uses [Http]; tests pass a fake. */
typealias EmbeddingPost = suspend (url: String, body: String, headers: Map<String, String>) -> HttpResponse

/**
 * Embeddings from one or more OpenAI-compatible routers (`POST {baseUrl}/embeddings`).
 *
 * Endpoints are tried in the order given, so the first is the preferred one and the rest are fallbacks. An endpoint that fails on
 * any part of a request (network error, an HTTP error such as 429 or 5xx, an answer that does not make sense) is skipped for that
 * whole request, and the next one is tried from the start, so one call never mixes vectors from two endpoints. If a [tag] is
 * asked for, only endpoints with that tag are tried.
 *
 * Texts are sent in batches of the endpoint's [EmbeddingEndpoint.batchSize]. Vectors are requested as plain floats, the default
 * every provider supports; a router that answers with base64 text instead is understood too.
 */
class RouterEmbedder(
    private val endpoints: List<EmbeddingEndpoint>,
    private val post: EmbeddingPost = { url, body, headers -> Http.postJson(url, body, headers, timeoutMs = 120_000) },
) : Embedder {
    init {
        require(endpoints.isNotEmpty()) { "At least one embedding endpoint is needed." }
    }

    override suspend fun embed(texts: List<String>, tag: String?): EmbeddingBatch {
        require(texts.isNotEmpty()) { "There is nothing to embed." }
        val usable = if (tag == null) endpoints else endpoints.filter { it.tag == tag }
        if (usable.isEmpty()) throw EmbeddingException("No embedding endpoint is set up for the model \"$tag\".")

        val failures = ArrayList<String>()
        for (endpoint in usable) {
            try {
                return embedWith(endpoint, texts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "${endpoint.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        throw EmbeddingException("No embedding endpoint worked. " + failures.joinToString("; "))
    }

    private suspend fun embedWith(endpoint: EmbeddingEndpoint, texts: List<String>): EmbeddingBatch {
        val out = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(endpoint.batchSize)) out += requestBatch(endpoint, batch)
        val dims = out.first().size
        if (out.any { it.size != dims }) throw EmbeddingException("the model returned vectors of different sizes")
        return EmbeddingBatch(endpoint.tag, out)
    }

    private suspend fun requestBatch(endpoint: EmbeddingEndpoint, batch: List<String>): List<FloatArray> {
        val body = JSONObject()
            .put("model", endpoint.model)
            .put("input", JSONArray(batch))
        endpoint.dimensions?.let { body.put("dimensions", it) }
        val headers = if (endpoint.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${endpoint.apiKey}")

        val resp = post("${endpoint.baseUrl.trimEnd('/')}/embeddings", body.toString(), headers)
        if (!resp.ok) throw EmbeddingException("HTTP ${resp.code}: ${resp.body.take(200)}")

        val data = try {
            JSONObject(resp.body).getJSONArray("data")
        } catch (e: JSONException) {
            throw EmbeddingException("the answer was not an embeddings list")
        }
        val slots = arrayOfNulls<FloatArray>(batch.size)
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: throw EmbeddingException("an entry in the answer was not an object")
            val index = item.optInt("index", i)
            if (index !in slots.indices || slots[index] != null) throw EmbeddingException("the answer numbered its vectors wrongly")
            slots[index] = parseVector(item.opt("embedding"))
        }
        if (slots.any { it == null }) throw EmbeddingException("expected ${batch.size} vectors, got ${data.length()}")
        return slots.map { it!! }
    }

    private fun parseVector(raw: Any?): FloatArray {
        val v = when (raw) {
            is JSONArray -> FloatArray(raw.length()) { raw.getDouble(it).toFloat() }
            is String -> VectorCodec.decodeAny(raw) ?: throw EmbeddingException("a base64 vector could not be read")
            else -> throw EmbeddingException("an entry had no vector")
        }
        if (v.isEmpty()) throw EmbeddingException("a vector was empty")
        if (v.any { it.isNaN() || it.isInfinite() }) throw EmbeddingException("a vector held a number that is not finite")
        return v
    }
}
