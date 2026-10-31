package ai.lily.app.sandbox

import ai.lily.app.net.Http
import org.json.JSONObject

data class PandaStackConfig(
    val apiKey: String,
    val apiUrl: String = "https://api.pandastack.ai",
    val template: String = "code-interpreter",
)

data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)

/** Pandastack REST client. See docs/pandastack.md for the endpoints and assumptions. */
class PandaStackClient(private val config: PandaStackConfig) {
    private val base = config.apiUrl.trimEnd('/')
    private val auth get() = mapOf("Authorization" to "Bearer ${config.apiKey}")

    suspend fun createSandbox(ttlSeconds: Int = 600): String {
        val body = JSONObject().put("template", config.template).put("ttl_seconds", ttlSeconds)
        val resp = Http.postJson("$base/v1/sandboxes", body.toString(), auth)
        if (!resp.ok) error("Pandastack create ${resp.code}: ${resp.body}")
        val json = JSONObject(resp.body)
        return json.optString("id").ifEmpty { json.optString("sandbox_id") }
            .ifEmpty { error("Pandastack create: no sandbox id in ${resp.body}") }
    }

    suspend fun exec(sandboxId: String, command: String): ExecResult {
        val body = JSONObject().put("cmd", command)
        val resp = Http.postJson("$base/v1/sandboxes/$sandboxId/exec", body.toString(), auth, 300_000)
        if (!resp.ok) error("Pandastack exec ${resp.code}: ${resp.body}")
        val json = JSONObject(resp.body)
        return ExecResult(
            stdout = json.optString("stdout"),
            stderr = json.optString("stderr"),
            exitCode = json.optInt("exit_code", json.optInt("exitCode", -1)),
        )
    }

    suspend fun kill(sandboxId: String) {
        Http.request("DELETE", "$base/v1/sandboxes/$sandboxId", headers = auth)
    }

    /** Creates a sandbox, runs one command string, and always deletes the sandbox. */
    suspend fun runOnce(command: String): ExecResult {
        val id = createSandbox()
        try {
            return exec(id, command)
        } finally {
            runCatching { kill(id) }
        }
    }
}
