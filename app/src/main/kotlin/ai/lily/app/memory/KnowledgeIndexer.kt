package ai.lily.app.memory

import java.util.UUID

/** What indexing one source did. [changed] is false when its chunks were already up to date and nothing was written. */
data class IndexResult(val sourceId: String, val chunks: Int, val changed: Boolean)

/**
 * Cuts library sources into chunks and stores them, so they can be searched.
 *
 * A source's chunks are current when the text has not changed since they were made and they were made with the same chunk
 * settings; otherwise they are rebuilt. The old chunks, the new chunks and the record that says so are written in one
 * transaction, so a failure leaves the old chunks in place. Nothing here changes the source itself.
 */
class KnowledgeIndexer(
    private val db: SqlDatabase,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val targetTokens: Int = Chunker.TARGET_TOKENS,
    private val overlapPercent: Int = Chunker.OVERLAP_PERCENT,
) {
    private val schema = SchemaGate(KnowledgeSchema.migrator(db))
    private val signature = Chunker.signature(targetTokens, overlapPercent)

    /** Returns null if there is no such source. With [force], chunks are rebuilt even when they are current. */
    suspend fun index(sourceId: String, force: Boolean = false): IndexResult? {
        schema.ensure()
        val source = db.execute(SELECT_CONTENT, listOf(sourceId)).rows.firstOrNull() ?: return null
        val content = source[0].orEmpty()
        val hash = source[1].orEmpty()
        if (!force) {
            val state = db.execute(SELECT_STATE, listOf(sourceId)).rows.firstOrNull()
            if (state != null && state[0] == hash && state[1] == signature) {
                return IndexResult(sourceId, state[2]?.toIntOrNull() ?: 0, changed = false)
            }
        }
        val chunks = Chunker.split(content, targetTokens, overlapPercent)
        val t = now()
        val statements = ArrayList<Stmt>()
        statements += Stmt(DELETE_CHUNKS, listOf(sourceId))
        chunks.chunked(ROWS_PER_INSERT).forEach { statements += insert(sourceId, it, t) }
        statements += Stmt(UPSERT_STATE, listOf(sourceId, hash, signature, chunks.size, t))
        db.transaction(statements)
        return IndexResult(sourceId, chunks.size, changed = true)
    }

    /** Indexes every source whose chunks are missing or out of date. Returns how many were rebuilt. */
    suspend fun indexStale(): Int {
        schema.ensure()
        val ids = db.execute(SELECT_STALE, listOf(signature)).rows.mapNotNull { it[0] }
        return ids.count { index(it)?.changed == true }
    }

    private fun insert(sourceId: String, batch: List<Chunk>, createdAt: Long): Stmt {
        val marks = batch.joinToString(", ") { "(?, ?, ?, ?, ?, ?, ?, ?)" }
        val args = batch.flatMap { listOf(newId(), sourceId, it.ordinal, it.startLine, it.endLine, it.text, it.tokens, createdAt) }
        return Stmt("INSERT INTO chunks (id, source_id, ordinal, start_line, end_line, text, tokens, created_at) VALUES $marks", args)
    }

    private companion object {
        /** Eight values per row; this keeps one statement far below SQLite's limit on values per statement. */
        const val ROWS_PER_INSERT = 50

        const val SELECT_CONTENT = "SELECT content, content_hash FROM sources WHERE id = ?"
        const val SELECT_STATE = "SELECT content_hash, settings, chunk_count FROM source_index WHERE source_id = ?"
        const val DELETE_CHUNKS = "DELETE FROM chunks WHERE source_id = ?"
        const val UPSERT_STATE = "INSERT INTO source_index (source_id, content_hash, settings, chunk_count, indexed_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (source_id) DO UPDATE SET content_hash = excluded.content_hash, settings = excluded.settings, chunk_count = excluded.chunk_count, indexed_at = excluded.indexed_at"
        const val SELECT_STALE = "SELECT s.id FROM sources s LEFT JOIN source_index i ON i.source_id = s.id WHERE i.source_id IS NULL OR i.content_hash <> s.content_hash OR i.settings <> ? ORDER BY s.created_at, s.id"
    }
}
