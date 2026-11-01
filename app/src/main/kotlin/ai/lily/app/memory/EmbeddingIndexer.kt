package ai.lily.app.memory

/** How many chunks got a vector in one run, and how many still lack one for this model. */
data class EmbedResult(val embedded: Int, val remaining: Int)

/**
 * Gives every chunk a meaning vector made by one pinned model ([tag]).
 *
 * A chunk is pending when it has no vector from [tag]. That covers new chunks, chunks rebuilt after their source changed (their
 * ids are new), and chunks whose old vector came from a different model, which the new one then replaces. Nothing here changes
 * the chunks themselves.
 *
 * Vectors are stored scaled to length 1, so similarity is a plain dot product. Each batch of vectors is written in one
 * transaction, so a failure part-way keeps what was already done and the next run carries on from there.
 *
 * Before each run, vectors whose chunk no longer exists (the source was deleted or rebuilt) are removed.
 */
class EmbeddingIndexer(
    private val db: SqlDatabase,
    private val embedder: Embedder,
    private val tag: String,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val batchSize: Int = DEFAULT_BATCH,
) {
    init {
        require(tag.isNotBlank()) { "The embedding model must be named." }
        require(batchSize > 0) { "The batch size must be positive." }
    }

    private val schema = SchemaGate(KnowledgeSchema.migrator(db))

    /** How many chunks have no vector from this model yet. */
    suspend fun pending(): Int {
        schema.ensure()
        return db.execute(COUNT_PENDING, listOf(tag)).rows.firstOrNull()?.get(0)?.toIntOrNull() ?: 0
    }

    /**
     * Embeds up to [maxChunks] pending chunks, oldest sources first. Throws [EmbeddingException] if the embedder cannot answer or
     * answers with a different model or vector size than before.
     */
    suspend fun embedPending(maxChunks: Int = DEFAULT_MAX): EmbedResult {
        schema.ensure()
        db.execute(PRUNE)
        val limit = maxChunks.coerceAtLeast(0)
        var done = 0
        while (done < limit) {
            val rows = db.execute(SELECT_PENDING, listOf(tag, minOf(batchSize, limit - done))).rows
            if (rows.isEmpty()) break

            val answer = embedder.embed(rows.map { it[2].orEmpty() }, tag)
            if (answer.tag != tag) throw EmbeddingException("Expected vectors from \"$tag\" but got \"${answer.tag}\".")
            if (answer.vectors.size != rows.size) throw EmbeddingException("Asked for ${rows.size} vectors and got ${answer.vectors.size}.")
            val unit = answer.vectors.map {
                VectorCodec.normalise(it) ?: throw EmbeddingException("The model returned an empty or invalid vector.")
            }
            val dims = unit.first().size
            if (unit.any { it.size != dims }) throw EmbeddingException("The model returned vectors of different sizes.")
            val known = db.execute(SELECT_DIMS, listOf(tag)).rows.firstOrNull()?.get(0)?.toIntOrNull()
            if (known != null && known != dims) {
                throw EmbeddingException("\"$tag\" now returns $dims numbers per vector but earlier vectors have $known. Clear them with reset() and embed again.")
            }

            val t = now()
            val statements = rows.indices.chunked(ROWS_PER_INSERT).map { insert(rows, unit, it, dims, t) }
            db.transaction(statements)
            done += rows.size
        }
        return EmbedResult(done, pending())
    }

    /** Deletes every vector from this model, for example after the router started serving a different model under the same id. */
    suspend fun reset(): Int {
        schema.ensure()
        return db.execute(DELETE_MODEL, listOf(tag)).affectedRows.toInt()
    }

    private fun insert(rows: List<List<String?>>, unit: List<FloatArray>, index: List<Int>, dims: Int, createdAt: Long): Stmt {
        val marks = index.joinToString(", ") { "(?, ?, ?, ?, ?, ?)" }
        val args = index.flatMap { i ->
            listOf(rows[i][0].orEmpty(), rows[i][1].orEmpty(), tag, dims, VectorCodec.encode(unit[i]), createdAt)
        }
        return Stmt("INSERT OR REPLACE INTO chunk_embeddings (chunk_id, source_id, model, dims, vector, created_at) VALUES $marks", args)
    }

    companion object {
        const val DEFAULT_BATCH = 32
        const val DEFAULT_MAX = 512

        /** Six values per row. */
        private const val ROWS_PER_INSERT = 16

        private const val SELECT_PENDING = "SELECT c.id, c.source_id, c.text FROM chunks c LEFT JOIN chunk_embeddings e ON e.chunk_id = c.id AND e.model = ? WHERE e.chunk_id IS NULL ORDER BY c.source_id, c.ordinal LIMIT ?"
        private const val COUNT_PENDING = "SELECT COUNT(*) FROM chunks c LEFT JOIN chunk_embeddings e ON e.chunk_id = c.id AND e.model = ? WHERE e.chunk_id IS NULL"
        private const val PRUNE = "DELETE FROM chunk_embeddings WHERE chunk_id NOT IN (SELECT id FROM chunks)"
        private const val SELECT_DIMS = "SELECT dims FROM chunk_embeddings WHERE model = ? LIMIT 1"
        private const val DELETE_MODEL = "DELETE FROM chunk_embeddings WHERE model = ?"
    }
}
