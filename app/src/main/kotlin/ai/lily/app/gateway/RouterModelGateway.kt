package ai.lily.app.gateway

import ai.lily.app.net.Http
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-compatible client for the self-hosted FreeLLMAPI router. */
class RouterModelGateway(private val config: RouterConfig) : ModelGateway {

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val messages = JSONArray().apply {
            request.system?.let { put(JSONObject().put("role", "system").put("content", it)) }
            put(JSONObject().put("role", "user").put("content", request.prompt))
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
}
