package ai.lily.app.gateway

import ai.lily.app.net.Http
import org.json.JSONObject

/** Asks an OpenAI-compatible router which models it offers. */
object RouterModels {
    /** The model ids, sorted; fails with a message the person can read (wrong address, wrong key, router down). */
    suspend fun list(config: RouterConfig): Result<List<String>> = runCatching {
        val resp = Http.request(
            "GET",
            "${config.baseUrl.trimEnd('/')}/models",
            headers = mapOf("Authorization" to "Bearer ${config.apiKey}"),
            timeoutMs = 20_000,
        )
        if (!resp.ok) error("The router answered ${resp.code}")
        parse(resp.body)
    }

    internal fun parse(body: String): List<String> {
        val data = JSONObject(body).getJSONArray("data")
        return (0 until data.length()).map { data.getJSONObject(it).getString("id") }.distinct().sorted()
    }
}
