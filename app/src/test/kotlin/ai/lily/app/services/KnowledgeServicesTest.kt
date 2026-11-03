package ai.lily.app.services

import ai.lily.app.memory.ConceptEmbedder
import ai.lily.app.memory.JdbcSqlDatabase
import ai.lily.app.memory.ScriptedGateway
import ai.lily.app.research.FakeFetcher
import ai.lily.app.research.FakeSearch
import ai.lily.app.research.FetchedPage
import ai.lily.app.research.ResearchBudget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The built stack end to end, on real SQLite in memory with scripted models and a fake web. */
class KnowledgeServicesTest {
    private lateinit var db: JdbcSqlDatabase
    private val question = "How do I cook spaghetti?"
    private val boil = "Boil the spaghetti until tender."

    @Before
    fun setUp() {
        db = JdbcSqlDatabase()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun withoutAnEmbeddingModelKeywordSearchAnswersAndSaysSo() = runBlocking<Unit> {
        val services = KnowledgeServices.build(
            db, ScriptedGateway("[]", "Boil it until tender [1]."), null, null, FakeSearch(), FakeFetcher(),
        )
        assertNull(services.embeddings)
        val notebook = services.repo.createNotebook("Dinner")
        val source = services.repo.addTextSource("Cooking", boil)
        services.repo.attachSource(notebook.id, source.info.id)

        val summary = services.indexAll()
        assertEquals(1, summary.rebuilt)
        assertEquals(0, summary.embedded)
        assertTrue(summary.note!!.contains("No embedding model"))

        val answer = services.asker.ask(question, notebook.id)
        assertEquals("Boil it until tender [1].", answer.text)
        assertEquals(source.info.id, answer.citations.single().sourceId)
        assertTrue(answer.notes.any { it.contains("Meaning search was skipped") })
    }

    @Test
    fun withAnEmbeddingModelChunksAndVectorsAreMadeAndMeaningSearchWorks() = runBlocking<Unit> {
        val services = KnowledgeServices.build(
            db, ScriptedGateway(), EmbeddingSetup(ConceptEmbedder(), "fake-model"), null, FakeSearch(), FakeFetcher(),
        )
        assertNotNull(services.embeddings)
        val notebook = services.repo.createNotebook("Dinner")
        val source = services.repo.addTextSource("Cooking", boil)
        services.repo.attachSource(notebook.id, source.info.id)

        val summary = services.indexAll()
        assertEquals(1, summary.rebuilt)
        assertEquals(1, summary.embedded)
        assertEquals(0, summary.remaining)
        assertNull(summary.note)

        val found = services.search.search("noodles", notebook.id)
        assertTrue(found.semantic)
        assertEquals(source.info.id, found.hits.first().hit.sourceId)

        val again = services.indexAll()
        assertEquals(0, again.rebuilt)
        assertEquals(0, again.embedded)
    }

    @Test
    fun ifVectorsCannotBeMadeTheChunksAreStillKeptAndKeywordSearchWorks() = runBlocking<Unit> {
        val embedder = ConceptEmbedder()
        embedder.failWith = "no endpoint"
        val services = KnowledgeServices.build(
            db, ScriptedGateway(), EmbeddingSetup(embedder, "fake-model"), null, FakeSearch(), FakeFetcher(),
        )
        val notebook = services.repo.createNotebook("Dinner")
        val source = services.repo.addTextSource("Cooking", boil)
        services.repo.attachSource(notebook.id, source.info.id)

        val summary = services.indexAll()
        assertEquals(1, summary.rebuilt)
        assertEquals(0, summary.embedded)
        assertNull(summary.remaining)
        assertTrue(summary.note!!.contains("keyword search still works"))

        val found = services.search.search("spaghetti", notebook.id)
        assertEquals(source.info.id, found.hits.first().hit.sourceId)
    }

    @Test
    fun theResearchRunnerUsesTheSameDatabaseAndAsker() = runBlocking<Unit> {
        val search = FakeSearch()
        val fetcher = FakeFetcher()
        val url = "https://a.example/pasta"
        search.answers[question] = listOf(url)
        fetcher.pages[url] = FetchedPage(url, "Pasta guide", boil, false)
        // Research planning, then the asker's planning, then the answer.
        val gateway = ScriptedGateway("[]", "[]", "Boil it until tender [1].")
        val services = KnowledgeServices.build(
            db, gateway, null, null, search, fetcher, ResearchBudget(minPageChars = 10),
        )

        val report = services.research.run(question)

        assertEquals(1, report.pages.size)
        assertTrue(report.answer!!.cited)
        assertNotNull(report.noteId)
        assertEquals(1, services.repo.listSources(report.notebookId).size)
        assertEquals(1, services.repo.listNotes(report.notebookId!!).size)
    }
}
