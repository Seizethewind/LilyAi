package ai.lily.app.core

data class JournalEntry(
    val id: Long = 0,
    val timestamp: Long,
    val project: String?,
    val kind: String,
    val content: String,
)

enum class EvidenceSource { SANDBOX_RUN, WEB_SOURCE, USER_CONFIRMED }

data class ProvenFact(
    val id: Long = 0,
    val timestamp: Long,
    val statement: String,
    val evidence: String,
    val source: EvidenceSource,
    val journalId: Long? = null,
)

/** Raw history: requests, decisions, results. */
interface Journal {
    suspend fun append(kind: String, content: String, project: String? = null): Long
    suspend fun recent(limit: Int = 20): List<JournalEntry>
}

/** Only verified statements, each with evidence. */
interface ProvenFacts {
    suspend fun promote(
        statement: String,
        evidence: String,
        source: EvidenceSource,
        journalId: Long? = null,
    ): Long

    suspend fun search(query: String, limit: Int = 10): List<ProvenFact>
}

/** Long-term memory: a Journal and a store of Proven facts, kept in two separate Turso databases. */
class Memory(val journal: Journal, val facts: ProvenFacts)
