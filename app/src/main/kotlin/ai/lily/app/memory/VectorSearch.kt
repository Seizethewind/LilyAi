package ai.lily.app.memory

/** A chunk and how close its meaning is to the query: 1.0 is the same direction, 0.0 unrelated, negative opposite. */
data class VectorHit(val chunkId: String, val similarity: Double)

/**
 * What a vector search looked at. [scanned] is how many stored vectors were compared; [complete] is false when there were more
 * that the scan limit stopped it from reading, so a closer chunk may exist that was not compared.
 */
data class VectorScan(val hits: List<VectorHit>, val scanned: Int, val complete: Boolean)

/**
 * Meaning search done in the app: it reads stored vectors over the network a page at a time and compares them with the query.
 *
 * This does not scale, and it says so: every search downloads every vector it compares (a 1536-number vector is about 8 KB of
 * text), so the scan stops after [DEFAULT_SCAN_LIMIT] vectors by default and reports [VectorScan.complete] = false. A library
 * with more chunks than that needs the database to do the comparison, which depends on the real Turso supporting vector search.
 *
 * Only vectors from the model with the asked-for tag and the same number of values as the query are compared. Chunks whose
 * vector cannot be read are skipped.
 */
class VectorSearch(private val db: SqlDatabase, private val pageSize: Int = PAGE_SIZE) {
    init {
        require(pageSize > 0) { "The page size must be positive." }
    }

    private val schema = SchemaGate(KnowledgeSchema.migrator(db))

    /**
     * The [limit] chunks closest in meaning to [query] (any length; it is compared by direction), closest first, among chunks
     * with a vector from [tag]. Only chunks with similarity above [minSimilarity] are returned. Pass [notebookId] to look only at
     * the sources linked to that notebook.
     */
    suspend fun nearest(
        query: FloatArray,
        tag: String,
        notebookId: String? = null,
        limit: Int = DEFAULT_LIMIT,
        scanLimit: Int = DEFAULT_SCAN_LIMIT,
        minSimilarity: Double = 0.0,
    ): VectorScan {
        schema.ensure()
        val queryNorm = VectorCodec.norm(query)
        if (query.isEmpty() || !queryNorm.isFinite() || queryNorm <= 0.0) return VectorScan(emptyList(), 0, true)

        val join = if (notebookId == null) "" else " JOIN notebook_sources ns ON ns.source_id = c.source_id AND ns.notebook_id = ?"
        val scope: List<Any> = if (notebookId == null) emptyList() else listOf(notebookId)
        val sql = "SELECT e.chunk_id, e.dims, e.vector FROM chunk_embeddings e JOIN chunks c ON c.id = e.chunk_id$join " +
            "WHERE e.model = ? AND e.chunk_id > ? ORDER BY e.chunk_id LIMIT ?"

        val found = ArrayList<VectorHit>()
        val cap = scanLimit.coerceAtLeast(1)
        var scanned = 0
        var last = ""
        while (scanned < cap) {
            val requested = minOf(pageSize, cap - scanned)
            val rows = db.execute(sql, scope + listOf<Any>(tag, last, requested)).rows
            for (r in rows) {
                val dims = r[1]?.toIntOrNull() ?: continue
                if (dims != query.size) continue
                val vector = VectorCodec.decode(r[2].orEmpty(), dims) ?: continue
                val vectorNorm = VectorCodec.norm(vector)
                if (!vectorNorm.isFinite() || vectorNorm <= 0.0) continue
                val similarity = VectorCodec.dot(query, vector) / (queryNorm * vectorNorm)
                if (similarity > minSimilarity) found += VectorHit(r[0].orEmpty(), similarity)
            }
            scanned += rows.size
            if (rows.size < requested) return finish(found, scanned, complete = true, limit = limit)
            last = rows.last()[0].orEmpty()
        }
        // The scan limit was reached with a full last page. Is there anything left that was not compared?
        val more = db.execute(sql, scope + listOf<Any>(tag, last, 1)).rows.isNotEmpty()
        return finish(found, scanned, complete = !more, limit = limit)
    }

    private fun finish(found: List<VectorHit>, scanned: Int, complete: Boolean, limit: Int): VectorScan {
        val best = found
            .sortedWith(compareByDescending<VectorHit> { it.similarity }.thenBy { it.chunkId })
            .take(limit.coerceIn(1, MAX_LIMIT))
        return VectorScan(best, scanned, complete)
    }

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
        const val DEFAULT_SCAN_LIMIT = 1500
        const val PAGE_SIZE = 100
    }
}
