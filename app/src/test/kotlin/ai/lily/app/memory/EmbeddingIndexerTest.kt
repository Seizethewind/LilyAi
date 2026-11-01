package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmbeddingIndexerTest {
    private var clock = 1_000L
    private var sourceCounter = 0
    private var chunkCounter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var chunker: KnowledgeIndexer
    private lateinit var embedder: ConceptEmbedder
    private lateinit var embeds: EmbeddingIndexer

    @Before
    fun setUp() {
        clock = 1_000L
        sourceCounter = 0
        chunkCounter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "s${++sourceCounter}" })
        chunker = KnowledgeIndexer(db, now = { clock++ }, newId = { "c${++chunkCounter}" })
        embedder = ConceptEmbedder()
        embeds = EmbeddingIndexer(db, embedder, "fake-model", now = { clock++ }, batchSize = 2)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(title: String, text: String): Source {
        val s = repo.addTextSource(title, text)
        chunker.index(s.info.id)
        return s
    }

    private suspend fun vectorCount(model: String? = null) =
        (if (model == null) db.execute("SELECT COUNT(*) FROM chunk_embeddings") else db.execute("SELECT COUNT(*) FROM chunk_embeddings WHERE model = ?", listOf(model)))
            .rows[0][0]!!.toInt()

    private fun lines(n: Int) = (1..n).joinToString("\n") { "L${"%02d".format(it)} ".padEnd(19, 'x') }

    @Test
    fun everyChunkGetsAUnitLengthVectorFromThePinnedModel() = runBlocking<Unit> {
        add("Cooking", "Boil the spaghetti.")
        add("Garden", "Tomato plants need sun.")
        add("Zoo", "The fox and the dog.")

        assertEquals(3, embeds.pending())
        val result = embeds.embedPending()

        assertEquals(EmbedResult(embedded = 3, remaining = 0), result)
        val rows = db.execute("SELECT model, dims, vector FROM chunk_embeddings").rows
        assertEquals(3, rows.size)
        rows.forEach { r ->
            assertEquals("fake-model", r[0])
            assertEquals("4", r[1])
            assertEquals(1.0, VectorCodec.norm(VectorCodec.decode(r[2]!!, 4)!!), 1e-5)
        }
    }

    @Test
    fun textsAreSentToTheModelInBatches() = runBlocking<Unit> {
        repeat(5) { add("Doc $it", "alpha $it") }

        embeds.embedPending()

        assertEquals(listOf(2, 2, 1), embedder.calls.map { it.size })
    }

    @Test
    fun aSecondRunDoesNothingAndANewChunkIsTheOnlyWork() = runBlocking<Unit> {
        add("Cooking", "Boil the spaghetti.")
        embeds.embedPending()
        embedder.calls.clear()

        assertEquals(EmbedResult(0, 0), embeds.embedPending())
        assertTrue(embedder.calls.isEmpty())

        add("Zoo", "The fox and the dog.")
        assertEquals(EmbedResult(1, 0), embeds.embedPending())
        assertEquals(listOf(listOf("The fox and the dog.")), embedder.calls)
    }

    @Test
    fun maxChunksStopsARunEarlyAndTheNextOneCarriesOn() = runBlocking<Unit> {
        repeat(5) { add("Doc $it", "alpha $it") }

        assertEquals(EmbedResult(3, 2), embeds.embedPending(maxChunks = 3))
        assertEquals(EmbedResult(2, 0), embeds.embedPending(maxChunks = 3))
        assertEquals(5, vectorCount())
    }

    @Test
    fun aFailureKeepsWhatWasAlreadyEmbedded() = runBlocking<Unit> {
        repeat(4) { add("Doc $it", "alpha $it") }
        var batches = 0
        val flaky = object : Embedder {
            override suspend fun embed(texts: List<String>, tag: String?): EmbeddingBatch {
                if (++batches == 2) throw EmbeddingException("router down")
                return embedder.embed(texts, tag)
            }
        }
        val flakyIndexer = EmbeddingIndexer(db, flaky, "fake-model", now = { clock++ }, batchSize = 2)

        val problem = try {
            flakyIndexer.embedPending()
            null
        } catch (e: EmbeddingException) {
            e
        }

        assertEquals("router down", problem!!.message)
        assertEquals(2, vectorCount())
        assertEquals(2, flakyIndexer.pending())
        assertEquals(EmbedResult(2, 0), flakyIndexer.embedPending())
    }

    @Test
    fun aRebuiltSourceGetsNewVectorsAndTheOldOnesAreRemoved() = runBlocking<Unit> {
        val page = repo.saveWebSource("https://example.com/p", "Page", lines(3))
        chunker.index(page.info.id)
        embeds.embedPending()
        val oldIds = db.execute("SELECT chunk_id FROM chunk_embeddings").rows.map { it[0] }.toSet()

        repo.saveWebSource("https://example.com/p", "Page", lines(4))
        chunker.index(page.info.id)
        assertEquals(1, embeds.pending())
        embeds.embedPending()

        val now = db.execute("SELECT chunk_id FROM chunk_embeddings").rows.map { it[0] }.toSet()
        assertEquals(1, now.size)
        assertTrue(now.none { it in oldIds })
    }

    @Test
    fun deletingASourceLeavesNoVectorsBehindAfterTheNextRun() = runBlocking<Unit> {
        val gone = add("Gone", "Boil the spaghetti.")
        add("Kept", "The fox and the dog.")
        embeds.embedPending()

        repo.deleteSource(gone.info.id)
        embeds.embedPending()

        assertEquals(1, vectorCount())
    }

    @Test
    fun aDifferentModelReplacesTheOldVectorsChunkByChunk() = runBlocking<Unit> {
        add("Cooking", "Boil the spaghetti.")
        embeds.embedPending()

        val next = EmbeddingIndexer(db, ConceptEmbedder(tag = "newer-model"), "newer-model", now = { clock++ })
        assertEquals(1, next.pending())
        next.embedPending()

        assertEquals(1, vectorCount())
        assertEquals(1, vectorCount("newer-model"))
        assertEquals(0, vectorCount("fake-model"))
    }

    @Test
    fun anAnswerFromTheWrongModelIsRefused() = runBlocking<Unit> {
        add("Cooking", "Boil the spaghetti.")
        embedder.answerTag = "something-else"

        val problem = try {
            embeds.embedPending()
            null
        } catch (e: EmbeddingException) {
            e
        }

        assertTrue(problem!!.message!!.contains("something-else"))
        assertEquals(0, vectorCount())
    }

    @Test
    fun aModelThatChangesItsVectorSizeIsCaughtUntilReset() = runBlocking<Unit> {
        add("Cooking", "Boil the spaghetti.")
        embeds.embedPending()
        add("Zoo", "The fox and the dog.")
        val bigger = EmbeddingIndexer(db, ConceptEmbedder(dims = 6), "fake-model", now = { clock++ })

        val problem = try {
            bigger.embedPending()
            null
        } catch (e: EmbeddingException) {
            e
        }
        assertTrue(problem!!.message!!.contains("reset"))
        assertEquals(1, vectorCount())

        assertEquals(1, bigger.reset())
        assertEquals(EmbedResult(2, 0), bigger.embedPending())
    }

    @Test
    fun nothingToEmbedNeverCallsTheModel() = runBlocking<Unit> {
        assertEquals(EmbedResult(0, 0), embeds.embedPending())
        assertTrue(embedder.calls.isEmpty())
    }
}
