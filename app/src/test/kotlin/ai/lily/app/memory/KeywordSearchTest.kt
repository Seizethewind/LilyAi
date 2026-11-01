package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real SQL against an in-memory SQLite: the database narrows the chunks down and the app ranks them. */
class KeywordSearchTest {
    private var clock = 1_000L
    private var sourceCounter = 0
    private var chunkCounter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var indexer: KnowledgeIndexer
    private lateinit var search: KeywordSearch

    private val cooking = "Boil the pasta in salted water.\nDrain the pasta and keep a cup of pasta water.\nToss with olive oil and garlic."
    private val garden = "Tomatoes need full sun.\nWater the tomatoes in the morning.\nPasta sauce uses tomatoes."
    private val unrelated = "The quick brown fox jumps over the lazy dog."

    @Before
    fun setUp() {
        clock = 1_000L
        sourceCounter = 0
        chunkCounter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "s${++sourceCounter}" })
        indexer = KnowledgeIndexer(db, now = { clock++ }, newId = { "c${++chunkCounter}" })
        search = KeywordSearch(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun add(title: String, text: String): Source {
        val s = repo.addTextSource(title, text)
        indexer.index(s.info.id)
        return s
    }

    @Test
    fun theBestMatchComesFirstAndSaysWhereItIs() = runBlocking<Unit> {
        val a = add("Cooking notes", cooking)
        val b = add("Garden journal", garden)
        add("Unrelated", unrelated)

        val hits = search.search("pasta")

        assertEquals(listOf(a.info.id, b.info.id), hits.map { it.sourceId })
        val top = hits[0]
        assertEquals("Cooking notes", top.sourceTitle)
        assertEquals(SourceKind.TEXT, top.sourceKind)
        assertNull(top.url)
        assertEquals(1, top.startLine)
        assertEquals(3, top.endLine)
        assertEquals(cooking, top.text)
        assertTrue(hits.zipWithNext().all { (x, y) -> x.score >= y.score })
        assertTrue(hits.all { it.score > 0.0 })
    }

    @Test
    fun caseDoesNotMatterAndEveryWordCounts() = runBlocking<Unit> {
        val a = add("Cooking notes", cooking)
        val b = add("Garden journal", garden)
        add("Unrelated", unrelated)

        val hits = search.search("PASTA Tomatoes")

        assertEquals(setOf(a.info.id, b.info.id), hits.map { it.sourceId }.toSet())
        assertEquals(b.info.id, hits[0].sourceId)
    }

    @Test
    fun aPartOfAWordIsNotAMatch() = runBlocking<Unit> {
        add("Cooking notes", cooking)
        add("Garden journal", garden)

        assertTrue(search.search("past").isEmpty())
        assertEquals(2, search.search("pasta").size)
    }

    @Test
    fun aRareWordCountsForMoreThanACommonOne() = runBlocking<Unit> {
        repeat(4) { add("Common $it", "common words only here $it") }
        val both = add("Both", "common and rare words")

        val hits = search.search("common rare")

        assertEquals(both.info.id, hits[0].sourceId)
        assertEquals(5, hits.size)
    }

    @Test
    fun theLineRangeOfALaterChunkIsReported() = runBlocking<Unit> {
        val text = (1..40).joinToString("\n") { i ->
            if (i == 35) "L35 needle ".padEnd(19, 'x') else "L${"%02d".format(i)} ".padEnd(19, 'x')
        }
        val s = repo.addTextSource("Long", text)
        KnowledgeIndexer(db, now = { clock++ }, newId = { "c${++chunkCounter}" }, targetTokens = 20, overlapPercent = 0).index(s.info.id)

        val hit = search.search("needle").single()

        assertEquals(33, hit.startLine)
        assertEquals(36, hit.endLine)
        assertTrue(hit.text.contains("L35 needle"))
    }

    @Test
    fun aSearchCanBeLimitedToOneNotebook() = runBlocking<Unit> {
        val a = add("Cooking notes", cooking)
        val b = add("Garden journal", garden)
        val inGarden = repo.createNotebook("Garden")
        val empty = repo.createNotebook("Empty")
        repo.attachSource(inGarden.id, b.info.id)

        assertEquals(setOf(a.info.id, b.info.id), search.search("pasta").map { it.sourceId }.toSet())
        assertEquals(listOf(b.info.id), search.search("pasta", notebookId = inGarden.id).map { it.sourceId })
        assertTrue(search.search("pasta", notebookId = empty.id).isEmpty())
        assertTrue(search.search("pasta", notebookId = "missing").isEmpty())
    }

    @Test
    fun aWebSourceReportsItsAddress() = runBlocking<Unit> {
        val web = repo.saveWebSource("https://example.com/Recipes", "Recipes", cooking)
        indexer.index(web.info.id)

        val hit = search.search("garlic").single()

        assertEquals(SourceKind.WEB, hit.sourceKind)
        assertEquals("https://example.com/Recipes", hit.url)
    }

    @Test
    fun theNumberOfHitsCanBeLimited() = runBlocking<Unit> {
        repeat(5) { add("Doc $it", "alpha beta $it") }

        assertEquals(5, search.search("alpha").size)
        assertEquals(2, search.search("alpha", limit = 2).size)
        assertEquals(1, search.search("alpha", limit = 0).size)
    }

    @Test
    fun nothingFoundIsAnEmptyList() = runBlocking<Unit> {
        add("Cooking notes", cooking)

        assertTrue(search.search("zebra").isEmpty())
        assertTrue(search.search("").isEmpty())
        assertTrue(search.search("  ?! ").isEmpty())
        assertTrue(search.search("100% _ \\").isEmpty())
    }

    @Test
    fun aSourceThatWasNeverIndexedIsNotFound() = runBlocking<Unit> {
        repo.addTextSource("Not indexed", cooking)

        assertTrue(search.search("pasta").isEmpty())
    }

    @Test
    fun aDeletedSourceIsNoLongerFound() = runBlocking<Unit> {
        val a = add("Cooking notes", cooking)
        add("Garden journal", garden)

        repo.deleteSource(a.info.id)

        assertEquals(listOf("Garden journal"), search.search("pasta").map { it.sourceTitle })
    }

    @Test
    fun anUpdatedPageIsFoundByItsNewTextOnceIndexed() = runBlocking<Unit> {
        val page = repo.saveWebSource("https://example.com/p", "Page", "old words about turnips")
        indexer.index(page.info.id)
        repo.saveWebSource("https://example.com/p", "Page", "new words about radishes")

        // Until it is indexed again, the old chunks answer.
        assertEquals(1, search.search("turnips").size)
        indexer.indexStale()
        assertTrue(search.search("turnips").isEmpty())
        assertNotNull(search.search("radishes").singleOrNull())
    }
}
