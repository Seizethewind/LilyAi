package ai.lily.app.memory

import ai.lily.app.net.Http

data class TursoConfig(val url: String, val authToken: String)

data class TursoResult(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val affectedRows: Long,
    val lastInsertRowid: Long?,
)

/** Turso over its HTTP API (v2 pipeline). No native libSQL dependency. */
class TursoClient(private val config: TursoConfig) : SqlDatabase {
    private val endpoint =
        config.url.trim().replaceFirst("libsql://", "https://").trimEnd('/') + "/v2/pipeline"

    private suspend fun post(body: String): String {
        val resp = Http.postJson(
            endpoint,
            body,
            mapOf("Authorization" to "Bearer ${config.authToken}"),
        )
        if (!resp.ok) error("Turso ${resp.code}: ${resp.body}")
        return resp.body
    }

    override suspend fun execute(sql: String, args: List<Any?>): TursoResult =
        TursoWire.parseExecute(post(TursoWire.executeBody(sql, args).toString()))

    override suspend fun transaction(statements: List<Stmt>): List<TursoResult> {
        if (statements.isEmpty()) return emptyList()
        return TursoWire.parseTransaction(
            post(TursoWire.transactionBody(statements).toString()),
            statements.size,
        )
    }
}
