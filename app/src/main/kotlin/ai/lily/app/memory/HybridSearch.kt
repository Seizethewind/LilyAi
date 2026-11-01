package ai.lily.app.memory

import kotlin.coroutines.cancellation.CancellationException

/**
 * A chunk found by keyword, by meaning, or both. [keywordRank] and [vectorRank] are its place (from 1) in each list, or null when
 * that list did not have it. [similarity] is its closeness in meaning when the meaning search had it. [score] is the fused score:
 * use it only to order hits, it is not a probability or a similarity. On [hit], `score` is the BM25 score for a keyword hit and the
 * similarity for a hit found only by meaning.
 */
data class HybridHit(
    val hit: SearchHit,
    val score: Double,
    val keywordRank: Int?,
    val vectorRank: Int?,
    val similarity: Double?,
)

/**
 * [semantic] is false when meaning search could not run (the embedding service was down, say) and only keywords were used.
 * [note] says why, or says that the meaning search looked at only part of the library; null when nothing needs saying.
 */
data class HybridResult(val hits: List<HybridHit>, val semantic: Boolean, val note: String?)

/**
 * Keyword search and meaning search together, joined with reciprocal rank fusion: a chunk scores 1 / (60 + rank) in each list it
 * appears in, and the two are added. It uses ranks, not raw scores, because BM25 scores and similarities are on different scales.
 * A chunk both lists like beats one only a single list likes.
 *
 * If the query cannot be embedded, the search still answers with the keyword hits and says so in [HybridResult.note].
 * The query is embedded with the same pinned model [tag] as the chunks; a different model is never used.
 */
class HybridSearch(
    private val db: SqlDatabase,
    private val embedder: Embedder,
    private val tag: String,
    private val keyword: KeywordSearch = KeywordSearch(db),
    private val vectors: VectorSearch = VectorSearch(db),
    private val scanLimit: Int = VectorSearch.DEFAULT_SCAN_LIMIT,
) {
    private val schema = SchemaGate(KnowledgeSchema.migrator(db))

    suspend fun search(
        query: String,
        notebookId: String? = null,
        limit: Int = DEFAULT_LIMIT,
        minSimilarity: Double = 0.0,
    ): HybridResult {
        if (query.isBlank()) return HybridResult(emptyList(), semantic = false, note = null)
        schema.ensure()
        val want = limit.coerceIn(1, MAX_LIMIT)
        val pool = (want * 3).coerceIn(MIN_POOL, KeywordSearch.MAX_LIMIT)

        val byKeyword = keyword.search(query, notebookId, pool)

        var failure: String? = null
        val scan: VectorScan? = try {
            val answer = embedder.embed(listOf(query.trim()), tag)
            if (answer.tag != tag) throw EmbeddingException("Expected a vector from \"$tag\" but got \"${answer.tag}\".")
            val raw = answer.vectors.singleOrNull() ?: throw EmbeddingException("The query did not get exactly one vector.")
            val unit = VectorCodec.normalise(raw) ?: throw EmbeddingException("The query vector was empty or invalid.")
            vectors.nearest(unit, tag, notebookId, pool, scanLimit, minSimilarity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = "Meaning search was skipped: ${e.message ?: e.javaClass.simpleName}"
            null
        }

        val note = when {
            failure != null -> failure
            scan != null && scan.scanned == 0 -> "No chunks have a meaning vector yet, so only keyword matches are shown."
            scan != null && !scan.complete -> "Only ${scan.scanned} vectors were compared; a closer match in meaning may have been missed."
            else -> null
        }

        val vectorHits = scan?.hits.orEmpty()
        val keywordRank = byKeyword.mapIndexed { i, h -> h.chunkId to (i + 1) }.toMap()
        val vectorRank = vectorHits.mapIndexed { i, h -> h.chunkId to (i + 1) }.toMap()
        val similarity = vectorHits.associate { it.chunkId to it.similarity }
        val byChunk = byKeyword.associateBy { it.chunkId }
        val onlyByMeaning = vectorHits.map { it.chunkId }.filter { it !in byChunk }
        val extra = fetch(onlyByMeaning, similarity).associateBy { it.chunkId }

        val fused = (keywordRank.keys + vectorRank.keys).mapNotNull { id ->
            val base = byChunk[id] ?: extra[id] ?: return@mapNotNull null
            val score = (keywordRank[id]?.let { 1.0 / (RRF_K + it) } ?: 0.0) + (vectorRank[id]?.let { 1.0 / (RRF_K + it) } ?: 0.0)
            HybridHit(base, score, keywordRank[id], vectorRank[id], similarity[id])
        }
        val hits = fused
            .sortedWith(
                compareByDescending<HybridHit> { it.score }
                    .thenBy { it.keywordRank ?: Int.MAX_VALUE }
                    .thenBy { it.hit.chunkId },
            )
            .take(want)
        return HybridResult(hits, semantic = scan != null, note = note)
    }

    /** Reads the chunks that only the meaning search found, with their sources. A chunk deleted in the meantime is simply absent. */
    private suspend fun fetch(ids: List<String>, similarity: Map<String, Double>): List<SearchHit> {
        if (ids.isEmpty()) return emptyList()
        val marks = ids.joinToString(", ") { "?" }
        val sql = "SELECT c.id, c.source_id, c.start_line, c.end_line, c.text, s.title, s.kind, s.url " +
            "FROM chunks c JOIN sources s ON s.id = c.source_id WHERE c.id IN ($marks)"
        return db.execute(sql, ids).rows.map { r ->
            val id = r[0].orEmpty()
            SearchHit(
                chunkId = id,
                sourceId = r[1].orEmpty(),
                sourceTitle = r[5].orEmpty(),
                sourceKind = SourceKind.valueOf(r[6].orEmpty()),
                url = r[7],
                startLine = r[2]?.toIntOrNull() ?: 0,
                endLine = r[3]?.toIntOrNull() ?: 0,
                text = r[4].orEmpty(),
                score = similarity[id] ?: 0.0,
            )
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
        private const val MIN_POOL = 10

        /** The usual constant for reciprocal rank fusion; it keeps one list's top spot from drowning out the other list. */
        private const val RRF_K = 60
    }
}
