package ai.lily.app.gateway

import ai.lily.app.net.Http
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-compatible client for the self-hosted FreeLLMAPI router. */
class RouterModelGateway(private val config: RouterConfig) : ModelGateway {

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val messages = JSONArray().apply {
            request.system?.let { put(JSONObject().put("role", "system").put("content", it)) }
            put(userMessage(request))
        }
        val body = JSONObject()
            .put("model", request.model ?: config.defaultModel)
            .put("messages", messages)

        val resp = Http.postJson(
            url = "${config.baseUrl.trimEnd('/')}/chat/completions",
            body = body.toString(),
            headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
            timeoutMs = 120_000,
        )
        if (!resp.ok) error("Router ${resp.code}: ${resp.body}")

        val content = JSONObject(resp.body)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return ModelResponse(content, resp.header("X-Routed-Via"))
    }

    companion object {
        /**
         * The user's message. Plain text stays a plain string, as before. With pictures it becomes the OpenAI vision form: a list of
         * a text part and one image part per picture, each sent as a data address.
         */
        internal fun userMessage(request: ModelRequest): JSONObject {
            if (request.images.isEmpty()) return JSONObject().put("role", "user").put("content", request.prompt)
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", request.prompt))
            for (image in request.images) {
                parts.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${image.mime};base64,${image.base64}")),
                )
            }
            return JSONObject().put("role", "user").put("content", parts)
        }
    }
}
