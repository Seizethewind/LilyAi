package ai.lily.app.memory

/** How the app will do a kind of search, decided by what the database supports. */
enum class SearchPath { IN_DATABASE, IN_APP }

class Capability(val name: String, val supported: Boolean, val detail: String)

class CapabilityReport(val transactions: Capability, val vector: Capability, val fullText: Capability) {
    /** Meaning search: Turso's vector functions, or cosine distance computed in the app. */
    val vectorSearch: SearchPath get() = if (vector.supported) SearchPath.IN_DATABASE else SearchPath.IN_APP

    /** Keyword search: Turso's full-text index (FTS5), or scoring in the app. */
    val keywordSearch: SearchPath get() = if (fullText.supported) SearchPath.IN_DATABASE else SearchPath.IN_APP

    fun summary(): String = listOf(
        line(transactions, "transactions"),
        line(vector, "vector search", vectorSearch),
        line(fullText, "full-text search", keywordSearch),
    ).joinToString("\n")

    private fun line(c: Capability, label: String, path: SearchPath? = null): String {
        val mark = if (c.supported) "yes" else "no"
        val how = when (path) {
            null -> ""
            SearchPath.IN_DATABASE -> " (done in the database)"
            SearchPath.IN_APP -> " (done in the app instead)"
        }
        return "$label: $mark$how. ${c.detail}"
    }
}

/**
 * Asks a database what it can do. Each check is run on its own and an error only means "not supported" for that one.
 * Nothing is left behind: the full-text check uses a temporary table inside a transaction.
 */
class CapabilityProbe(private val db: SqlDatabase) {

    private suspend fun attempt(name: String, okDetail: String, block: suspend () -> Boolean): Capability =
        try {
            if (block()) Capability(name, true, okDetail) else Capability(name, false, "The database answered, but not as expected.")
        } catch (e: Exception) {
            Capability(name, false, (e.message ?: e::class.simpleName ?: "error").lineSequence().first().take(160))
        }

    suspend fun run(): CapabilityReport {
        val tx = attempt("transactions", "Several statements ran as one unit.") {
            db.transaction(listOf(Stmt("SELECT 1"), Stmt("SELECT 2"))).size == 2
        }
        val vector = attempt("vector", "vector32 and vector_distance_cos work.") {
            val r = db.execute("SELECT vector_distance_cos(vector32('[1,2,3]'), vector32('[1,2,4]'))")
            r.rows.firstOrNull()?.firstOrNull()?.toDoubleOrNull() != null
        }
        val fts = attempt("fullText", "FTS5 tables and MATCH work.") {
            val results = db.transaction(
                listOf(
                    Stmt("CREATE VIRTUAL TABLE temp.lily_fts_probe USING fts5(body)"),
                    Stmt("INSERT INTO temp.lily_fts_probe (body) VALUES ('hello knowledge world')"),
                    Stmt("SELECT count(*) FROM temp.lily_fts_probe WHERE lily_fts_probe MATCH 'knowledge'"),
                    Stmt("DROP TABLE temp.lily_fts_probe"),
                ),
            )
            results[2].rows.firstOrNull()?.firstOrNull()?.toLongOrNull() == 1L
        }
        return CapabilityReport(tx, vector, fts)
    }
}
