package ai.lily.app.services

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.memory.ContextRepository
import ai.lily.app.memory.Embedder
import ai.lily.app.memory.EmbeddingBatch
import ai.lily.app.memory.EmbeddingException
import ai.lily.app.memory.EmbeddingIndexer
import ai.lily.app.memory.HybridSearch
import ai.lily.app.memory.InsightGenerator
import ai.lily.app.memory.KnowledgeAsker
import ai.lily.app.memory.KnowledgeIndexer
import ai.lily.app.memory.KnowledgeRepository
import ai.lily.app.memory.NotebookChat
import ai.lily.app.memory.NotebookContextBuilder
import ai.lily.app.memory.SqlDatabase
import ai.lily.app.research.PageFetcher
import ai.lily.app.research.ResearchBudget
import ai.lily.app.research.ResearchRunner
import ai.lily.app.research.SearchProvider
import kotlin.coroutines.cancellation.CancellationException

/** An embedder and the tag its vectors are stored under. */
class EmbeddingSetup(val embedder: Embedder, val tag: String)

/** What [KnowledgeServices.indexAll] did. [remaining] is null when it is not known (making vectors failed). */
data class IndexSummary(val rebuilt: Int, val embedded: Int, val remaining: Int?, val note: String?)

/** Stands in when no embedding model is set up: every call fails with a clear reason, and search falls back to keywords. */
private object UnavailableEmbedder : Embedder {
    override suspend fun embed(texts: List<String>, tag: String?): EmbeddingBatch =
        throw EmbeddingException(KnowledgeServices.NO_EMBEDDING)
}

/**
 * The knowledge layer, built once from one database, one model gateway and the embedding setup, so every screen and worker uses
 * the same objects, the same pinned embedding tag and the same database. Nothing here talks to a network when it is built;
 * the knowledge database's tables are created the first time something uses them.
 *
 * Without an embedding setup ([embeddings] is null) everything still works on keyword search, and answers say that meaning
 * search was skipped.
 */
class KnowledgeServices(
    val repo: KnowledgeRepository,
    val context: ContextRepository,
    val indexer: KnowledgeIndexer,
    val embeddings: EmbeddingIndexer?,
    val search: HybridSearch,
    val contextBuilder: NotebookContextBuilder,
    val asker: KnowledgeAsker,
    val insights: InsightGenerator,
    val chat: NotebookChat,
    val research: ResearchRunner,
) {
    /**
     * Cuts every source that is new or changed into chunks, then gives chunks without a vector one (if a model is set up). Call it
     * after adding or changing sources. A failure to make vectors is reported in [IndexSummary.note] and does not undo the chunks.
     */
    suspend fun indexAll(maxEmbed: Int = EmbeddingIndexer.DEFAULT_MAX): IndexSummary {
        val rebuilt = indexer.indexStale()
        val vectors = embeddings ?: return IndexSummary(rebuilt, 0, null, NO_EMBEDDING)
        return try {
            val r = vectors.embedPending(maxEmbed)
            IndexSummary(rebuilt, r.embedded, r.remaining, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IndexSummary(rebuilt, 0, null, "Meaning vectors could not be made (${e.message ?: e.javaClass.simpleName}); keyword search still works.")
        }
    }

    companion object {
        const val NO_EMBEDDING = "No embedding model is set up in Settings, so only keyword search is used."
        private const val NO_MODEL_TAG = "none"

        /** [model] is the router selector used for planning, answering, insights and chat; null lets the gateway choose. */
        fun build(
            db: SqlDatabase,
            gateway: ModelGateway,
            embedding: EmbeddingSetup?,
            model: String?,
            searchProvider: SearchProvider,
            fetcher: PageFetcher,
            budget: ResearchBudget = ResearchBudget(),
        ): KnowledgeServices {
            val repo = KnowledgeRepository(db)
            val context = ContextRepository(db)
            val indexer = KnowledgeIndexer(db)
            val vectors = embedding?.let { EmbeddingIndexer(db, it.embedder, it.tag) }
            val search = HybridSearch(db, embedding?.embedder ?: UnavailableEmbedder, embedding?.tag ?: NO_MODEL_TAG)
            val contextBuilder = NotebookContextBuilder(repo, context)
            val asker = KnowledgeAsker(gateway, search, repo, model, contextBuilder)
            return KnowledgeServices(
                repo = repo,
                context = context,
                indexer = indexer,
                embeddings = vectors,
                search = search,
                contextBuilder = contextBuilder,
                asker = asker,
                insights = InsightGenerator(gateway, repo, context, model),
                chat = NotebookChat(gateway, asker, context, model),
                research = ResearchRunner(
                    gateway = gateway,
                    search = searchProvider,
                    fetcher = fetcher,
                    repo = repo,
                    indexer = indexer,
                    asker = asker,
                    embeddings = vectors,
                    model = model,
                    budget = budget,
                ),
            )
        }
    }
}
