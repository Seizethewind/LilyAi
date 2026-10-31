package ai.lily.app.memory

import ai.lily.app.net.Http
import org.json.JSONArray
import org.json.JSONObject

data class TursoConfig(val url: String, val authToken: String)

data class TursoResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val affectedRows: Long,
    val lastInsertRowid: Long?,
)

/** Turso over its HTTP API (v2 pipeline). No native libSQL dependency. */
class TursoClient(private val config: TursoConfig) {
    private val endpoint =
        config.url.trim().replaceFirst("libsql://", "https://").trimEnd('/') + "/v2/pipeline"

    suspend fun execute(sql: String, args: List<Any?> = emptyList()): TursoResult {
        val stmt = JSONObject()
            .put("sql", sql)
            .put("args", JSONArray().apply { args.forEach { put(encode(it)) } })
        val body = JSONObject().put(
            "requests",
            JSONArray()
                .put(JSONObject().put("type", "execute").put("stmt", stmt))
                .put(JSONObject().put("type", "close"))
        )
        val resp = Http.postJson(
            endpoint,
            body.toString(),
            mapOf("Authorization" to "Bearer ${config.authToken}"),
        )
        if (!resp.ok) error("Turso ${resp.code}: ${resp.body}")

        val first = JSONObject(resp.body).getJSONArray("results").getJSONObject(0)
        if (first.getString("type") == "error") {
            error("Turso error: ${first.getJSONObject("error").optString("message")}")
        }
        val result = first.getJSONObject("response").getJSONObject("result")

        val cols = result.optJSONArray("cols") ?: JSONArray()
        val columns = (0 until cols.length()).map { cols.getJSONObject(it).optString("name") }
        val rawRows = result.optJSONArray("rows") ?: JSONArray()
        val rows = (0 until rawRows.length()).map { r ->
            val row = rawRows.getJSONArray(r)
            (0 until row.length()).map { c ->
                val cell = row.getJSONObject(c)
                if (cell.optString("type") == "null") null else cell.optString("value")
            }
        }
        return TursoResult(
            columns = columns,
            rows = rows,
            affectedRows = result.optLong("affected_rows"),
            lastInsertRowid = result.optString("last_insert_rowid").toLongOrNull(),
        )
    }

    private fun encode(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("type", "null")
        is Int, is Long -> JSONObject().put("type", "integer").put("value", v.toString())
        is Double, is Float -> JSONObject().put("type", "float").put("value", (v as Number).toDouble())
        else -> JSONObject().put("type", "text").put("value", v.toString())
    }
}
