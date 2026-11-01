package ai.lily.app.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON that goes to and comes back from Turso's HTTP API (v2 pipeline), kept apart from the network call so it can be tested.
 *
 * A transaction is sent as one `batch` request: BEGIN, then each statement (each only if the one before succeeded), then COMMIT,
 * then a ROLLBACK that only runs if COMMIT did not succeed. Step 0 is BEGIN, steps 1..N are the statements, step N+1 is COMMIT
 * and step N+2 is ROLLBACK.
 */
object TursoWire {
    fun encodeValue(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("type", "null")
        is Int, is Long -> JSONObject().put("type", "integer").put("value", v.toString())
        is Double, is Float -> JSONObject().put("type", "float").put("value", (v as Number).toDouble())
        else -> JSONObject().put("type", "text").put("value", v.toString())
    }

    fun stmt(sql: String, args: List<Any?> = emptyList()): JSONObject =
        JSONObject()
            .put("sql", sql)
            .put("args", JSONArray().apply { args.forEach { put(encodeValue(it)) } })

    fun executeBody(sql: String, args: List<Any?>): JSONObject =
        JSONObject().put(
            "requests",
            JSONArray()
                .put(JSONObject().put("type", "execute").put("stmt", stmt(sql, args)))
                .put(JSONObject().put("type", "close")),
        )

    private fun okAt(step: Int): JSONObject = JSONObject().put("type", "ok").put("step", step)

    fun transactionBody(statements: List<Stmt>): JSONObject {
        require(statements.isNotEmpty()) { "a transaction needs at least one statement" }
        val n = statements.size
        val steps = JSONArray()
        steps.put(JSONObject().put("stmt", stmt("BEGIN")))
        statements.forEachIndexed { i, s ->
            steps.put(JSONObject().put("condition", okAt(i)).put("stmt", stmt(s.sql, s.args)))
        }
        steps.put(JSONObject().put("condition", okAt(n)).put("stmt", stmt("COMMIT")))
        steps.put(
            JSONObject()
                .put("condition", JSONObject().put("type", "not").put("cond", okAt(n + 1)))
                .put("stmt", stmt("ROLLBACK")),
        )
        return JSONObject().put(
            "requests",
            JSONArray()
                .put(JSONObject().put("type", "batch").put("batch", JSONObject().put("steps", steps)))
                .put(JSONObject().put("type", "close")),
        )
    }

    /** Reads one `result` object (columns, rows, counts). */
    fun parseResult(result: JSONObject): TursoResult {
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

    private fun firstResult(body: String): JSONObject {
        val first = JSONObject(body).getJSONArray("results").getJSONObject(0)
        if (first.getString("type") == "error") {
            error("Turso error: ${first.getJSONObject("error").optString("message")}")
        }
        return first.getJSONObject("response").getJSONObject("result")
    }

    fun parseExecute(body: String): TursoResult = parseResult(firstResult(body))

    /** Reads the answer to [transactionBody]. Throws with the database's message if the transaction did not commit. */
    fun parseTransaction(body: String, statementCount: Int): List<TursoResult> {
        val result = firstResult(body)
        val errors = result.optJSONArray("step_errors") ?: JSONArray()
        val results = result.optJSONArray("step_results") ?: JSONArray()
        val commit = statementCount + 1

        fun errorAt(i: Int): String? =
            if (i >= errors.length() || errors.isNull(i)) null else errors.getJSONObject(i).optString("message")

        val commitFailed = errorAt(commit) != null || commit >= results.length() || results.isNull(commit)
        if (commitFailed) {
            val why = (0..commit).firstNotNullOfOrNull { errorAt(it) } ?: "the transaction did not commit"
            error("Turso transaction rolled back: $why")
        }
        return (1..statementCount).map { i ->
            if (results.isNull(i)) error("Turso transaction rolled back: statement $i did not run")
            parseResult(results.getJSONObject(i))
        }
    }
}
