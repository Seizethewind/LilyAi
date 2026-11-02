package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A notebook search only looks at FULL sources; a search of the whole library ignores levels. */
class ContextLevelSearchTest {
    private var clock = 1_000L
    private var counter = 0
    private var chunkCounter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var ctx: ContextRepository
    private lateinit var keyword: KeywordSearch
    private lateinit var hybrid: HybridSearch
    private lateinit var nbId: String
    private lateinit var cookingId: String
    private lateinit var gardenId: String

    @Before
    fun setUp() = runBlocking<Unit> {
        clock = 1_000L
        counter = 0
        chunkCounter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "s${++counter}" })
        ctx = ContextRepository(db, now = { clock++ }, newId = { "x${++counter}" })
        val chunker = KnowledgeIndexer(db, now = { clock++ }, newId = { "c${++chunkCounter}" })
        val embedder = ConceptEmbedder()
        val embeds = EmbeddingIndexer(db, embedder, "fake-model", now = { clock++ })
        keyword = KeywordSearch(db)
        hybrid = HybridSearch(db, embedder, "fake-model")

        val nb = repo.createNotebook("Nb")
        nbId = nb.id
        val cooking = repo.addTextSource("Cooking", "Boil the spaghetti until tender.")
        val garden = repo.addTextSource("Garden", "Tomato plants need full sun.")
        cookingId = cooking.info.id
        gardenId = garden.info.id
        listOf(cooking, garden).forEach {
            repo.attachSource(nb.id, it.info.id)
            chunker.index(it.info.id)
        }
        embeds.embedPending()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun keywordSearchSkipsSourcesThatAreNotFull() = runBlocking<Unit> {
        assertEquals(listOf(cookingId), keyword.search("spaghetti", nbId).map { it.sourceId })

        ctx.setLevel(nbId, cookingId, ContextLevel.OFF)
        assertTrue(keyword.search("spaghetti", nbId).isEmpty())

        ctx.setLevel(nbId, cookingId, ContextLevel.INSIGHTS)
        assertTrue(keyword.search("spaghetti", nbId).isEmpty())

        ctx.setLevel(nbId, cookingId, ContextLevel.FULL)
        assertEquals(listOf(cookingId), keyword.search("spaghetti", nbId).map { it.sourceId })
    }

    @Test
    fun searchingTheWholeLibraryIgnoresLevels() = runBlocking<Unit> {
        ctx.setLevel(nbId, cookingId, ContextLevel.OFF)

        assertEquals(listOf(cookingId), keyword.search("spaghetti").map { it.sourceId })
        assertEquals(listOf(cookingId), hybrid.search("noodles").hits.map { it.hit.sourceId })
    }

    @Test
    fun meaningSearchSkipsSourcesThatAreNotFull() = runBlocking<Unit> {
        // "noodles" shares no word with the text, so only the meaning search can find it.
        assertEquals(listOf(cookingId), hybrid.search("noodles", nbId).hits.map { it.hit.sourceId })

        ctx.setLevel(nbId, cookingId, ContextLevel.INSIGHTS)
        val result = hybrid.search("noodles", nbId)
        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun aLevelInOneNotebookDoesNotAffectAnother() = runBlocking<Unit> {
        val other = repo.createNotebook("Other")
        repo.attachSource(other.id, cookingId)
        ctx.setLevel(nbId, cookingId, ContextLevel.OFF)

        assertTrue(keyword.search("spaghetti", nbId).isEmpty())
        assertEquals(listOf(cookingId), keyword.search("spaghetti", other.id).map { it.sourceId })
        assertEquals(listOf(cookingId), hybrid.search("noodles", other.id).hits.map { it.hit.sourceId })
    }

    @Test
    fun otherSourcesInTheNotebookStillAnswer() = runBlocking<Unit> {
        ctx.setLevel(nbId, cookingId, ContextLevel.OFF)

        assertEquals(listOf(gardenId), keyword.search("tomato", nbId).map { it.sourceId })
        assertEquals(listOf(gardenId), hybrid.search("garden", nbId).hits.map { it.hit.sourceId })
    }
}
