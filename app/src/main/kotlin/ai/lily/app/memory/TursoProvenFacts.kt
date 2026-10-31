package ai.lily.app.memory

import ai.lily.app.core.EvidenceSource
import ai.lily.app.core.ProvenFact
import ai.lily.app.core.ProvenFacts

/** Proven facts store: only verified statements, each with evidence. */
class TursoProvenFacts(private val db: TursoClient) : ProvenFacts {
    private var ready = false

    private suspend fun init() {
        if (ready) return
        db.execute(
            "CREATE TABLE IF NOT EXISTS proven_facts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER NOT NULL, statement TEXT NOT NULL, evidence TEXT NOT NULL, " +
                "source TEXT NOT NULL, journal_id INTEGER)"
        )
        ready = true
    }

    override suspend fun promote(
        statement: String,
        evidence: String,
        source: EvidenceSource,
        journalId: Long?,
    ): Long {
        require(statement.isNotBlank()) { "statement is empty" }
        require(evidence.isNotBlank()) { "a proven fact needs evidence" }
        init()
        val r = db.execute(
            "INSERT INTO proven_facts (ts, statement, evidence, source, journal_id) VALUES (?, ?, ?, ?, ?)",
            listOf(System.currentTimeMillis(), statement, evidence, source.name, journalId),
        )
        return r.lastInsertRowid ?: -1L
    }

    override suspend fun search(query: String, limit: Int): List<ProvenFact> {
        init()
        val r = db.execute(
            "SELECT id, ts, statement, evidence, source, journal_id FROM proven_facts " +
                "WHERE statement LIKE ? ORDER BY ts DESC LIMIT ?",
            listOf("%$query%", limit),
        )
        return r.rows.map {
            ProvenFact(
                id = it[0]?.toLongOrNull() ?: 0L,
                timestamp = it[1]?.toLongOrNull() ?: 0L,
                statement = it[2].orEmpty(),
                evidence = it[3].orEmpty(),
                source = runCatching { EvidenceSource.valueOf(it[4].orEmpty()) }
                    .getOrDefault(EvidenceSource.USER_CONFIRMED),
                journalId = it[5]?.toLongOrNull(),
            )
        }
    }
}
