package ai.lily.app.memory

import ai.lily.app.gateway.EmbeddingEndpoint
import ai.lily.app.gateway.RouterEmbedder
import ai.lily.app.net.HttpResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The request and answer JSON, batching and failover, with a fake in place of the network. */
class RouterEmbedderTest {
    private class Call(val url: String, val body: JSONObject, val headers: Map<String, String>)

    private val calls = ArrayList<Call>()

    /** Answers every input with the vector [length, 0]; a reply per URL prefix can be set. */
    private fun fake(answer: (Call) -> HttpResponse): suspend (String, String, Map<String, String>) -> HttpResponse =
        { url, body, headers ->
            val call = Call(url, JSONObject(body), headers)
            calls += call
            answer(call)
        }

    private fun ok(body: JSONObject) = HttpResponse(200, body.toString(), emptyMap())

    private fun lengthVectors(call: Call, shuffled: Boolean = false): JSONObject {
        val input = call.body.getJSONArray("input")
        val items = (0 until input.length()).map { i ->
            JSONObject().put("object", "embedding").put("index", i)
                .put("embedding", JSONArray().put(input.getString(i).length.toDouble()).put(0.0))
        }
        val ordered = if (shuffled) items.reversed() else items
        return JSONObject().put("object", "list").put("data", JSONArray(ordered))
    }

    private fun endpoint(name: String, url: String, model: String = "m", key: String = "k", batch: Int = 32, dims: Int? = null) =
        EmbeddingEndpoint(name, url, key, model, dims, batch)

    private inline fun <reified T : Throwable> failure(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun theRequestIsTheOpenAiShape() = runBlocking<Unit> {
        val e = RouterEmbedder(listOf(endpoint("nine", "http://r:20128/v1/", model = "openai/text-embedding-3-small", dims = 256)), fake { ok(lengthVectors(it)) })

        val out = e.embed(listOf("hello", "hi"))

        val call = calls.single()
        assertEquals("http://r:20128/v1/embeddings", call.url)
        assertEquals("Bearer k", call.headers["Authorization"])
        assertEquals("openai/text-embedding-3-small", call.body.getString("model"))
        assertEquals(256, call.body.getInt("dimensions"))
        assertEquals(listOf("hello", "hi"), (0 until 2).map { call.body.getJSONArray("input").getString(it) })
        assertEquals("openai/text-embedding-3-small@256", out.tag)
        assertEquals(listOf(5f, 2f), out.vectors.map { it[0] })
    }

    @Test
    fun noKeyMeansNoAuthorizationHeaderAndNoDimensionsMeansNoField() = runBlocking<Unit> {
        val e = RouterEmbedder(listOf(endpoint("nine", "http://r/v1", key = "")), fake { ok(lengthVectors(it)) })

        e.embed(listOf("x"))

        assertFalse(calls.single().headers.containsKey("Authorization"))
        assertFalse(calls.single().body.has("dimensions"))
    }

    @Test
    fun vectorsComeBackInInputOrderEvenIfTheAnswerIsShuffled() = runBlocking<Unit> {
        val e = RouterEmbedder(listOf(endpoint("nine", "http://r/v1")), fake { ok(lengthVectors(it, shuffled = true)) })

        val out = e.embed(listOf("a", "bb", "ccc"))

        assertEquals(listOf(1f, 2f, 3f), out.vectors.map { it[0] })
    }

    @Test
    fun manyTextsAreSentInBatches() = runBlocking<Unit> {
        val e = RouterEmbedder(listOf(endpoint("nine", "http://r/v1", batch = 2)), fake { ok(lengthVectors(it)) })

        val out = e.embed(listOf("a", "bb", "ccc", "dddd", "eeeee"))

        assertEquals(listOf(2, 2, 1), calls.map { it.body.getJSONArray("input").length() })
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f), out.vectors.map { it[0] })
    }

    @Test
    fun aFailingEndpointIsSkippedAndTheNextOneAnswers() = runBlocking<Unit> {
        val e = RouterEmbedder(
            listOf(endpoint("nine", "http://nine/v1", batch = 1), endpoint("free", "http://free/v1", batch = 1)),
            fake { if (it.url.startsWith("http://nine") && it.body.getJSONArray("input").getString(0) == "b") HttpResponse(503, "busy", emptyMap()) else ok(lengthVectors(it)) },
        )

        val out = e.embed(listOf("a", "b"))

        // 9Router answered the first text, failed on the second; the whole request then restarts on the other one.
        assertEquals(listOf("nine", "nine", "free", "free"), calls.map { it.url.substringAfter("//").substringBefore("/") })
        assertEquals(listOf(1f, 1f), out.vectors.map { it[0] })
    }

    @Test
    fun askingForATagOnlyUsesEndpointsWithThatTag() = runBlocking<Unit> {
        val e = RouterEmbedder(
            listOf(endpoint("a", "http://a/v1", model = "model-a"), endpoint("b", "http://b/v1", model = "model-b")),
            fake { ok(lengthVectors(it)) },
        )

        val out = e.embed(listOf("x"), tag = "model-b")
        assertEquals("model-b", out.tag)
        assertEquals(listOf("http://b/v1/embeddings"), calls.map { it.url })

        calls.clear()
        val problem = failure<EmbeddingException> { runBlocking { e.embed(listOf("x"), tag = "model-c") } }
        assertTrue(problem.message!!.contains("model-c"))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun twoEndpointsWithTheSameTagBackEachOtherUp() = runBlocking<Unit> {
        val nine = EmbeddingEndpoint("nine", "http://nine/v1", "", "openai/text-embedding-3-small", tag = "te3-small")
        val free = EmbeddingEndpoint("free", "http://free/v1", "", "text-embedding-3-small", tag = "te3-small")
        val e = RouterEmbedder(listOf(nine, free), fake { if (it.url.startsWith("http://nine")) HttpResponse(429, "slow down", emptyMap()) else ok(lengthVectors(it)) })

        val out = e.embed(listOf("x"), tag = "te3-small")

        assertEquals("te3-small", out.tag)
        assertEquals("text-embedding-3-small", calls.last().body.getString("model"))
    }

    @Test
    fun whenEveryEndpointFailsTheMessageNamesEach() = runBlocking<Unit> {
        val e = RouterEmbedder(
            listOf(endpoint("nine", "http://nine/v1"), endpoint("free", "http://free/v1")),
            fake { HttpResponse(500, "boom", emptyMap()) },
        )

        val problem = failure<EmbeddingException> { runBlocking { e.embed(listOf("x")) } }

        assertTrue(problem.message!!.contains("nine: HTTP 500"))
        assertTrue(problem.message!!.contains("free: HTTP 500"))
    }

    @Test
    fun aNetworkErrorCountsAsAFailureToo() = runBlocking<Unit> {
        val e = RouterEmbedder(listOf(endpoint("nine", "http://nine/v1")), { _, _, _ -> throw java.io.IOException("no route") })

        val problem = failure<EmbeddingException> { runBlocking { e.embed(listOf("x")) } }

        assertTrue(problem.message!!.contains("no route"))
    }

    @Test
    fun badAnswersAreRejected() = runBlocking<Unit> {
        fun withAnswer(json: String) = RouterEmbedder(listOf(endpoint("nine", "http://nine/v1")), fake { HttpResponse(200, json, emptyMap()) })

        // too few vectors
        failure<EmbeddingException> { runBlocking { withAnswer("""{"data":[{"index":0,"embedding":[1,2]}]}""").embed(listOf("a", "b")) } }
        // the same index twice
        failure<EmbeddingException> { runBlocking { withAnswer("""{"data":[{"index":0,"embedding":[1]},{"index":0,"embedding":[1]}]}""").embed(listOf("a", "b")) } }
        // an empty vector
        failure<EmbeddingException> { runBlocking { withAnswer("""{"data":[{"index":0,"embedding":[]}]}""").embed(listOf("a")) } }
        // not an embeddings list
        failure<EmbeddingException> { runBlocking { withAnswer("""{"error":"nope"}""").embed(listOf("a")) } }
        // vectors of different sizes in one answer
        failure<EmbeddingException> { runBlocking { withAnswer("""{"data":[{"index":0,"embedding":[1,2]},{"index":1,"embedding":[1]}]}""").embed(listOf("a", "b")) } }
    }

    @Test
    fun aRouterThatAnswersInBase64IsUnderstood() = runBlocking<Unit> {
        val text = VectorCodec.encode(floatArrayOf(0.5f, -1f, 2f))
        val e = RouterEmbedder(
            listOf(endpoint("nine", "http://nine/v1")),
            fake { HttpResponse(200, JSONObject().put("data", JSONArray().put(JSONObject().put("index", 0).put("embedding", text))).toString(), emptyMap()) },
        )

        val out = e.embed(listOf("x"))

        assertEquals(listOf(0.5f, -1f, 2f), out.vectors.single().toList())
    }

    @Test
    fun autoIsNotAcceptableAsAModel() {
        failure<IllegalArgumentException> { endpoint("nine", "http://nine/v1", model = "auto") }
        failure<IllegalArgumentException> { endpoint("nine", "http://nine/v1", model = "Auto:fast") }
        failure<IllegalArgumentException> { endpoint("nine", "http://nine/v1", model = " ") }
        failure<IllegalArgumentException> { endpoint("nine", "http://nine/v1", batch = 0) }
        failure<IllegalArgumentException> { RouterEmbedder(emptyList()) }
        assertNull(null) // keeps the import used if the checks above change
    }
}
