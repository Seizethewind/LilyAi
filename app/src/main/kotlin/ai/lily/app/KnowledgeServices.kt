package ai.lily.app

import ai.lily.app.gateway.RouterEmbedder
import ai.lily.app.memory.Embedder
import ai.lily.app.memory.EmbeddingIndexer
import ai.lily.app.memory.IndexResult
import ai.lily.app.memory.KnowledgeIndexer
import ai.lily.app.memory.SqlDatabase
import ai.lily.app.memory.TursoClient
import ai.lily.app.settings.AppSettings

/**
 * The knowledge layer built once from the settings, so screens and Lily get the same pieces instead of each wiring their own.
 * [embedder] and [embeddingIndexer] are null when no embedding service is set; search then stays keyword only.
 */
class KnowledgeServices(
    val db: SqlDatabase,
    val indexer: KnowledgeIndexer,
    val embedder: Embedder?,
    val embeddingIndexer: EmbeddingIndexer?,
) {
    /**
     * Cuts one source into chunks and, when an embedding service is set, gives new chunks their vectors. Returns null if the
     * source does not exist. The chunks are saved before the vectors are asked for, so an EmbeddingException from here leaves
     * the source searchable by keyword and the next call carries on with the vectors.
     */
    suspend fun indexSource(sourceId: String): IndexResult? {
        val result = indexer.index(sourceId)
        if (result != null) embeddingIndexer?.embedPending()
        return result
    }

    companion object {
        /** Null when no knowledge database is set. */
        fun create(settings: AppSettings): KnowledgeServices? {
            val config = settings.knowledgeDb ?: return null
            val db = TursoClient(config)
            val endpoint = settings.embedding
            val embedder = endpoint?.let { RouterEmbedder(listOf(it)) }
            val vectors = if (endpoint != null && embedder != null) EmbeddingIndexer(db, embedder, endpoint.tag) else null
            return KnowledgeServices(db, KnowledgeIndexer(db), embedder, vectors)
        }
    }
}
