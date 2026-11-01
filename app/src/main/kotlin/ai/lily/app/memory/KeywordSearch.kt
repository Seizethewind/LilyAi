package ai.lily.app.memory

import kotlin.math.ln

/** One chunk that matched a search. [startLine] and [endLine] are line numbers in the source's text, counted from 1. */
data class SearchHit(
    val chunkId: String,
    val sourceId: String,
    val sourceTitle: String,
    val sourceKind: SourceKind,
    val url: String?,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val score: Double,
)

/**
 * Keyword search over the chunks of the library, or of one notebook's sources.
 *
 * It does not need the database to support full-text search. The database narrows the chunks down to those that contain any
 * of the words as text (a plain `LIKE`), and the app ranks them with BM25 on whole words. So a chunk only counts for a word it
 * really contains: "past" does not find "pasta".
 *
 * Limits, on purpose and worth knowing:
 * - Case is ignored for English letters only; the database's `LIKE` does not fold other alphabets.
 * - Only the best [CANDIDATE_LIMIT] chunks by number of matching words are ranked, so in a very large library a weak match
 *   can be missed.
 * - The word counts behind the ranking count a word wherever it appears inside another word, which slightly softens the weight
 *   of rare words.
 */
class KeywordSearch(private val db: SqlDatabase) {
    private val schema = SchemaGate(KnowledgeSchema.migrator(db))

    /** Best matches first. An empty or all-punctuation query finds nothing. */
    suspend fun search(query: String, notebookId: String? = null, limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val terms = KnowledgeTokenizer.queryTerms(query)
        if (terms.isEmpty()) return emptyList()
        schema.ensure()

        val like = terms.map { "%$it%" }
        val join = if (notebookId == null) "" else " JOIN notebook_sources ns ON ns.source_id = c.source_id AND ns.notebook_id = ?"
        val scope = if (notebookId == null) emptyList() else listOf(notebookId)

        // How many chunks there are, how long they are on average, and how many contain each word.
        val countEach = terms.joinToString("") { ", SUM(CASE WHEN c.text LIKE ? THEN 1 ELSE 0 END)" }
        val stats = db.execute("SELECT COUNT(*), AVG(c.tokens)$countEach FROM chunks c$join", like + scope).rows.firstOrNull()
            ?: return emptyList()
        val total = stats[0]?.toLongOrNull() ?: 0L
        if (total == 0L) return emptyList()
        val averageTokens = stats[1]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val idf = terms.indices.map { i ->
            val df = (stats[2 + i]?.toLongOrNull() ?: 0L).coerceIn(0L, total).toDouble()
            ln(1.0 + (total - df + 0.5) / (df + 0.5))
        }

        // The chunks that contain at least one word, those with the most words first.
        val matchesAny = terms.joinToString(" OR ") { "c.text LIKE ?" }
        val matchCount = terms.joinToString(" + ") { "(CASE WHEN c.text LIKE ? THEN 1 ELSE 0 END)" }
        val sql = "SELECT c.id, c.source_id, c.start_line, c.end_line, c.text, c.tokens, s.title, s.kind, s.url " +
            "FROM chunks c JOIN sources s ON s.id = c.source_id$join " +
            "WHERE ($matchesAny) ORDER BY ($matchCount) DESC, c.source_id, c.ordinal LIMIT ?"
        val rows = db.execute(sql, scope + like + like + CANDIDATE_LIMIT).rows

        val hits = rows.mapNotNull { r ->
            val text = r[4].orEmpty()
            val counts = KnowledgeTokenizer.words(text).groupingBy { it }.eachCount()
            val length = (r[5]?.toDoubleOrNull() ?: 1.0).coerceAtLeast(1.0)
            val norm = K1 * (1.0 - B + B * length / averageTokens)
            var score = 0.0
            terms.forEachIndexed { i, term ->
                val tf = (counts[term] ?: 0).toDouble()
                if (tf > 0.0) score += idf[i] * tf * (K1 + 1.0) / (tf + norm)
            }
            if (score <= 0.0) {
                null
            } else {
                SearchHit(
                    chunkId = r[0].orEmpty(),
                    sourceId = r[1].orEmpty(),
                    sourceTitle = r[6].orEmpty(),
                    sourceKind = SourceKind.valueOf(r[7].orEmpty()),
                    url = r[8],
                    startLine = r[2]?.toIntOrNull() ?: 0,
                    endLine = r[3]?.toIntOrNull() ?: 0,
                    text = text,
                    score = score,
                )
            }
        }
        return hits
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.sourceId }.thenBy { it.startLine })
            .take(limit.coerceIn(1, MAX_LIMIT))
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
        const val CANDIDATE_LIMIT = 500

        // BM25: how fast repeating a word stops helping, and how much a long chunk is held back.
        private const val K1 = 1.2
        private const val B = 0.75
    }
}
