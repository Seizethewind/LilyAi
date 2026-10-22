package com.seizethewind.lilyai.api.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Rows returned by one SQL statement. Every cell is text or null. */
typealias Rows = List<List<String?>>

/**
 * Minimal client for Turso's SQL-over-HTTP API (POST {url}/v2/pipeline).
 * Accepts the URL as shown by Turso (libsql://...) or as https://...
 */
class TursoClient(rawUrl: String, private val token: String) {

    private val baseUrl = normalize(rawUrl)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Runs the statements in order in one request. Each statement is SQL plus positional
     * arguments (String, Long, Int, Boolean or null). Returns one [Rows] per statement.
     */
    suspend fun run(statements: List<Pair<String, List<Any?>>>): Result<List<Rows>> =
        withContext(Dispatchers.IO) {
            if (statements.isEmpty()) {
                return@withContext Result.success(emptyList<Rows>())
            }
            try {
                val requests = JSONArray()
                for ((sql, args) in statements) {
                    val stmt = JSONObject().put("sql", sql)
                    if (args.isNotEmpty()) {
                        val array = JSONArray()
                        args.forEach { array.put(encodeArg(it)) }
                        stmt.put("args", array)
                    }
                    requests.put(JSONObject().put("type", "execute").put("stmt", stmt))
                }
                requests.put(JSONObject().put("type", "close"))
                val body = JSONObject().put("requests", requests).toString()

                val request = Request.Builder()
                    .url("$baseUrl/v2/pipeline")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.code == 401 || response.code == 403 ->
                            Result.failure<List<Rows>>(
                                IOException("Turso rejected the token. Check it in Cloud settings.")
                            )
                        !response.isSuccessful ->
                            Result.failure<List<Rows>>(
                                IOException("Turso error ${response.code}: ${errorDetail(text)}")
                            )
                        else -> parse(text, statements.size)
                    }
                }
            } catch (e: IOException) {
                Result.failure<List<Rows>>(
                    IOException("Could not reach Turso. Check your connection and the database URL.")
                )
            } catch (e: IllegalArgumentException) {
                Result.failure<List<Rows>>(IOException("The Turso URL is not valid."))
            } catch (e: JSONException) {
                Result.failure<List<Rows>>(IOException("Unexpected reply from Turso."))
            }
        }

    private fun encodeArg(value: Any?): JSONObject =
        when (value) {
            null -> JSONObject().put("type", "null")
            is Long -> JSONObject().put("type", "integer").put("value", value.toString())
            is Int -> JSONObject().put("type", "integer").put("value", value.toString())
            is Boolean -> JSONObject().put("type", "integer").put("value", if (value) "1" else "0")
            else -> JSONObject().put("type", "text").put("value", value.toString())
        }

    private fun parse(text: String, count: Int): Result<List<Rows>> {
        val results = JSONObject(text).getJSONArray("results")
        val out = ArrayList<Rows>()
        for (i in 0 until count) {
            val item = results.getJSONObject(i)
            if (item.optString("type") != "ok") {
                val message = item.optJSONObject("error")?.optString("message").orEmpty()
                return Result.failure(IOException("Turso query failed: $message"))
            }
            val rowsJson = item.getJSONObject("response")
                .getJSONObject("result")
                .getJSONArray("rows")
            val rows = ArrayList<List<String?>>()
            for (r in 0 until rowsJson.length()) {
                val rowJson = rowsJson.getJSONArray(r)
                val row = ArrayList<String?>()
                for (c in 0 until rowJson.length()) row.add(cell(rowJson, c))
                rows.add(row)
            }
            out.add(rows)
        }
        return Result.success(out)
    }

    private fun cell(row: JSONArray, index: Int): String? {
        if (row.isNull(index)) return null
        val value = row.get(index)
        if (value is JSONObject) {
            if (value.optString("type") == "null") return null
            return if (value.has("value")) value.get("value").toString() else null
        }
        return value.toString()
    }

    private fun errorDetail(text: String): String =
        try {
            JSONObject(text).optString("error").ifBlank { text.take(120) }
        } catch (e: JSONException) {
            text.take(120)
        }

    companion object {
        fun normalize(raw: String): String {
            val url = raw.trim()
            val withScheme = when {
                url.startsWith("libsql://") -> "https://" + url.removePrefix("libsql://")
                url.startsWith("https://") || url.startsWith("http://") -> url
                else -> "https://$url"
            }
            return withScheme.trimEnd('/').removeSuffix("/v2/pipeline").trimEnd('/')
        }
    }
}
