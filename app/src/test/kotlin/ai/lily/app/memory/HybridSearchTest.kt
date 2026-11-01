package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real SQL against an in-memory SQLite, with the concept embedder standing in for a model. */
class HybridSearchTest {
    private var clock = 1_000L
    private var sourceCounter = 0
    private var chunkCounter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var chunker: KnowledgeIndexer
    private lateinit var embedder: ConceptEmbedder
    private lateinit var embeds: EmbeddingIndexer
    private lateinit var hybrid: HybridSearch

    @Before
    fun setUp() {
        clock = 1_000L
        sourceCounter = 0
        chunkCounter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "s${++sourceCounter}" })
        chunker = KnowledgeIndexer(db, now = { clock++ }, newId = { "c${++chunkCounter}" })
        embedder = ConceptEmbedder()
        embeds = EmbeddingIndexer(db, embedder, "fake-model", now = { clock++ })
        hybrid = HybridSearch(db, embedder, "fake-model")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(title: String, text: String): Source {
        val s = repo.addTextSource(title, text)
        chunker.index(s.info.id)
        return s
    }

    private suspend fun library(): Triple<Source, Source, Source> {
        val cooking = add("Cooking", "Boil the spaghetti until tender.")
        val garden = add("Garden", "Tomato plants need full sun.")
        val zoo = add("Zoo", "The fox and the dog live here.")
        embeds.embedPending()
        return Triple(cooking, garden, zoo)
    }

    @Test
    fun aQueryFindsTheRightChunkByMeaningWithoutSharingAWord() = runBlocking<Unit> {
        val (cooking, _, _) = library()

        val result = hybrid.search("noodles")

        assertTrue(result.semantic)
        assertNull(result.note)
        val hit = result.hits.single()
        assertEquals(cooking.info.id, hit.hit.sourceId)
        assertNull(hit.keywordRank)
        assertEquals(1, hit.vectorRank)
        assertTrue(hit.similarity!! > 0.99)
    }

    @Test
    fun keywordSearchAloneMissesWhatMeaningSearchFinds() = runBlocking<Unit> {
        library()

        assertTrue(KeywordSearch(db).search("noodles").isEmpty())
        assertEquals(1, hybrid.search("noodles").hits.size)
    }

    @Test
    fun aChunkBothListsLikeBeatsOneOnlyOneLikes() = runBlocking<Unit> {
        val cooking = add("Cooking", "Boil the spaghetti until tender.")
        val menu = add("Menu", "Pasta of the day is penne.")
        val essay = add("Essay", "Spaghetti westerns are long films about gunfights and revenge and dusty towns.")
        embeds.embedPending()

        val result = hybrid.search("spaghetti")

        // Cooking and the essay both contain the word; the essay is weaker by meaning. The menu has the meaning but not the word.
        val bySource = result.hits.associateBy { it.hit.sourceId }
        assertEquals(setOf(cooking.info.id, menu.info.id, essay.info.id), bySource.keys)
        assertEquals(cooking.info.id, result.hits[0].hit.sourceId)
        assertNotNull(bySource.getValue(cooking.info.id).keywordRank)
        assertNotNull(bySource.getValue(cooking.info.id).vectorRank)
        assertNull(bySource.getValue(menu.info.id).keywordRank)
        assertTrue(result.hits.zipWithNext().all { (a, b) -> a.score >= b.score })
        val top = result.hits[0]
        assertEquals(1.0 / 61 + 1.0 / 61, top.score, 1e-9)
    }

    @Test
    fun ifTheModelCannotBeReachedKeywordHitsStillComeBackWithAReason() = runBlocking<Unit> {
        val (cooking, _, _) = library()
        embedder.failWith = "router down"

        val result = hybrid.search("spaghetti")

        assertFalse(result.semantic)
        assertTrue(result.note!!.contains("router down"))
        assertEquals(listOf(cooking.info.id), result.hits.map { it.hit.sourceId })
        assertNull(result.hits[0].vectorRank)
    }

    @Test
    fun withNoVectorsYetItSaysSoAndStillAnswersByKeyword() = runBlocking<Unit> {
        add("Cooking", "Boil the spaghetti until tender.")

        val result = hybrid.search("spaghetti")

        assertTrue(result.semantic)
        assertTrue(result.note!!.contains("No chunks have a meaning vector"))
        assertEquals(1, result.hits.size)
    }

    @Test
    fun theQueryIsEmbeddedWithThePinnedModelOnly() = runBlocking<Unit> {
        library()
        embedder.answerTag = "other-model"

        val result = hybrid.search("spaghetti")

        assertFalse(result.semantic)
        assertTrue(result.note!!.contains("other-model"))
    }

    @Test
    fun vectorsFromAnotherModelAreNotCompared() = runBlocking<Unit> {
        library()
        val other = HybridSearch(db, ConceptEmbedder(tag = "newer-model"), "newer-model")

        val result = other.search("noodles")

        assertTrue(result.hits.isEmpty())
        assertTrue(result.note!!.contains("No chunks have a meaning vector"))
    }

    @Test
    fun aSearchCanBeLimitedToOneNotebook() = runBlocking<Unit> {
        val (cooking, garden, _) = library()
        val nb = repo.createNotebook("Dinner")
        repo.attachSource(nb.id, garden.info.id)

        assertEquals(listOf(cooking.info.id), hybrid.search("noodles").hits.map { it.hit.sourceId })
        assertTrue(hybrid.search("noodles", notebookId = nb.id).hits.isEmpty())
        repo.attachSource(nb.id, cooking.info.id)
        assertEquals(listOf(cooking.info.id), hybrid.search("noodles", notebookId = nb.id).hits.map { it.hit.sourceId })
    }

    @Test
    fun minSimilarityDropsWeakMeaningMatches() = runBlocking<Unit> {
        library()

        // "fox noodles" is half about animals, half about pasta: both are about 0.7 similar.
        assertEquals(2, hybrid.search("fox noodles").hits.size)
        assertEquals(0, hybrid.search("fox noodles", minSimilarity = 0.9).hits.size)
    }

    @Test
    fun theNumberOfHitsCanBeLimited() = runBlocking<Unit> {
        repeat(6) { add("Pasta $it", "Penne number $it") }
        embeds.embedPending()

        assertEquals(6, hybrid.search("noodles").hits.size)
        assertEquals(2, hybrid.search("noodles", limit = 2).hits.size)
        assertEquals(1, hybrid.search("noodles", limit = 0).hits.size)
    }

    @Test
    fun aBlankQueryFindsNothingAndNeverCallsTheModel() = runBlocking<Unit> {
        library()
        embedder.calls.clear()

        val result = hybrid.search("   ")

        assertTrue(result.hits.isEmpty())
        assertFalse(result.semantic)
        assertTrue(embedder.calls.isEmpty())
    }

    @Test
    fun aVectorSearchThatStopsAtItsLimitSaysItWasIncomplete() = runBlocking<Unit> {
        repeat(5) { add("Pasta $it", "Penne number $it") }
        embeds.embedPending()
        val small = HybridSearch(db, embedder, "fake-model", vectors = VectorSearch(db, pageSize = 2), scanLimit = 3)

        val result = small.search("noodles")

        assertTrue(result.semantic)
        assertTrue(result.note!!.contains("Only 3 vectors"))
        assertEquals(3, result.hits.size)
    }

    @Test
    fun theScanIsCompleteWhenTheLimitIsExactlyTheLibrarySize() = runBlocking<Unit> {
        repeat(4) { add("Pasta $it", "Penne number $it") }
        embeds.embedPending()
        val vectors = VectorSearch(db, pageSize = 2)
        val query = VectorCodec.normalise(embedder.embed(listOf("noodles")).vectors.single())!!

        val exact = vectors.nearest(query, "fake-model", scanLimit = 4)
        val short = vectors.nearest(query, "fake-model", scanLimit = 3)
        val roomy = vectors.nearest(query, "fake-model", scanLimit = 100)

        assertEquals(4, exact.scanned)
        assertTrue(exact.complete)
        assertFalse(short.complete)
        assertEquals(3, short.scanned)
        assertTrue(roomy.complete)
        assertEquals(4, roomy.scanned)
    }

    @Test
    fun aDeletedSourceIsNeverReturnedEvenBeforeItsVectorsArePruned() = runBlocking<Unit> {
        val (cooking, _, _) = library()

        repo.deleteSource(cooking.info.id)

        assertTrue(hybrid.search("noodles").hits.isEmpty())
    }
}
