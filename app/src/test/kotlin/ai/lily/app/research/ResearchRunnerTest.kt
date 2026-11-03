package ai.lily.app.research

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.memory.ConceptEmbedder
import ai.lily.app.memory.EmbeddingIndexer
import ai.lily.app.memory.HybridSearch
import ai.lily.app.memory.JdbcSqlDatabase
import ai.lily.app.memory.KnowledgeAsker
import ai.lily.app.memory.KnowledgeIndexer
import ai.lily.app.memory.KnowledgeRepository
import ai.lily.app.memory.NoteOrigin
import ai.lily.app.memory.ScriptedGateway
import ai.lily.app.memory.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Each query maps to a list of addresses, or to a Throwable to throw. */
class FakeSearch : SearchProvider {
    val answers = HashMap<String, Any>()
    val queries = ArrayList<String>()

    /** Addresses a query returns (only for queries set with a list). */
    fun addressesFor(query: String): List<String> {
        val answer = answers[query]
        return if (answer is List<*>) answer.filterIsInstance<String>() else emptyList()
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        queries += query
        val answer = answers[query] ?: return emptyList()
        if (answer is Throwable) throw answer
        return addressesFor(query).take(limit).map { SearchResult(it, it, "") }
    }
}

/** Each address maps to a [FetchedPage], or to a Throwable to throw. An address not listed is a 404. */
class FakeFetcher : PageFetcher {
    val pages = HashMap<String, Any>()
    val fetched = ArrayList<String>()
    var onFetch: () -> Unit = {}

    override suspend fun fetch(url: String): FetchedPage {
        fetched += url
        onFetch()
        val answer = pages[url] ?: throw FetchException("The page answered with HTTP 404.")
        if (answer is Throwable) throw answer
        return answer as FetchedPage
    }
}

class ResearchRunnerTest {
    private var clock = 1_000L
    private var idCounter = 0
    private var chunkCounter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var chunker: KnowledgeIndexer
    private lateinit var embedder: ConceptEmbedder
    private lateinit var embeds: EmbeddingIndexer
    private lateinit var hybrid: HybridSearch
    private lateinit var search: FakeSearch
    private lateinit var fetcher: FakeFetcher

    private val question = "How do I cook spaghetti?"
    private val boil = "Boil the spaghetti until tender. Salt the water well."
    private val penne = "Penne and noodles cook fast in salted water."

    @Before
    fun setUp() {
        clock = 1_000L
        idCounter = 0
        chunkCounter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "id${++idCounter}" })
        chunker = KnowledgeIndexer(db, now = { clock++ }, newId = { "c${++chunkCounter}" })
        embedder = ConceptEmbedder()
        embeds = EmbeddingIndexer(db, embedder, "fake-model", now = { clock++ })
        hybrid = HybridSearch(db, embedder, "fake-model")
        search = FakeSearch()
        fetcher = FakeFetcher()
    }

    @After
    fun tearDown() = db.close()

    private fun page(url: String, title: String, text: String, truncated: Boolean = false) =
        FetchedPage(url, title, text, truncated)

    private fun runner(
        plan: ModelGateway,
        ask: ModelGateway,
        budget: ResearchBudget = ResearchBudget(minPageChars = 10),
        withEmbeddings: Boolean = true,
        now: () -> Long = { 0L },
    ) = ResearchRunner(
        gateway = plan,
        search = search,
        fetcher = fetcher,
        repo = repo,
        indexer = chunker,
        asker = KnowledgeAsker(ask, hybrid, repo),
        embeddings = if (withEmbeddings) embeds else null,
        budget = budget,
        now = now,
    )

    @Test
    fun aFullRunSearchesReadsSavesAndWritesACitedReport() = runBlocking<Unit> {
        search.answers[question] = listOf("https://a.example/pasta")
        search.answers["noodles"] = listOf("https://b.example/noodles")
        fetcher.pages["https://a.example/pasta"] = page("https://a.example/pasta", "Pasta guide", boil)
        fetcher.pages["https://b.example/noodles"] = page("https://b.example/noodles", "Noodle notes", penne)
        val planner = ScriptedGateway("""["noodles"]""")
        val answerer = ScriptedGateway("[]", "Boil it until tender [1].")
        val stages = ArrayList<ResearchStage>()

        val report = runner(planner, answerer).run(question, onProgress = { stages += it.stage })

        assertEquals(listOf(question, "noodles"), report.queries)
        assertEquals(listOf(question, "noodles"), search.queries)
        assertEquals(2, report.pages.size)
        assertTrue(report.pages.all { it.chunks >= 1 })
        assertTrue(report.skipped.isEmpty())

        val notebook = repo.getNotebook(report.notebookId!!)!!
        assertEquals("Research: How do I cook spaghetti?", notebook.name)
        val sources = repo.listSources(notebook.id)
        assertEquals(2, sources.size)
        assertTrue(sources.all { it.kind == SourceKind.WEB })
        assertEquals(setOf("https://a.example/pasta", "https://b.example/noodles"), sources.map { it.url }.toSet())

        val answer = report.answer!!
        assertEquals("Boil it until tender [1].", answer.text)
        assertTrue(answer.cited)
        val note = repo.getNote(report.noteId!!)!!
        assertEquals(NoteOrigin.AI, note.origin)
        assertEquals(1, repo.listCitedSources(note.id).size)
        assertTrue(note.body.contains("Sources"))

        assertEquals(ResearchStage.PLANNING, stages.first())
        assertEquals(ResearchStage.DONE, stages.last())
        assertTrue(stages.containsAll(listOf(ResearchStage.SEARCHING, ResearchStage.READING, ResearchStage.INDEXING, ResearchStage.ANSWERING)))

        val text = report.toText()
        assertTrue(text.startsWith("Boil it until tender [1]."))
        assertTrue(text.contains("Pages read (2)"))
        assertTrue(text.contains("\nSources\n[1] "))
    }

    @Test
    fun ifPlanningFailsOnlyTheQuestionIsSearched() = runBlocking<Unit> {
        search.answers[question] = listOf("https://a.example/pasta")
        fetcher.pages["https://a.example/pasta"] = page("https://a.example/pasta", "Pasta guide", boil)

        val report = runner(ScriptedGateway(IOException("router down")), ScriptedGateway("[]", "Boil it [1].")).run(question)

        assertEquals(listOf(question), report.queries)
        assertTrue(report.notes.any { it.contains("Planning the searches failed") && it.contains("router down") })
        assertEquals(1, report.pages.size)
    }

    @Test
    fun resultsAreInterleavedSoOneSearchCannotTakeEveryPage() = runBlocking<Unit> {
        search.answers[question] = listOf("https://u.example/1", "https://u.example/2", "https://u.example/3")
        search.answers["b"] = listOf("https://v.example/1", "https://v.example/2", "https://v.example/3")
        for (u in search.addressesFor(question) + search.addressesFor("b")) {
            fetcher.pages[u] = page(u, u, boil)
        }
        val budget = ResearchBudget(maxPages = 2, maxFetchAttempts = 6, minPageChars = 10)

        val report = runner(ScriptedGateway("""["b"]"""), ScriptedGateway("[]", "Boil [1]."), budget).run(question)

        assertEquals(listOf("https://u.example/1", "https://v.example/1"), fetcher.fetched)
        assertEquals(2, report.pages.size)
    }

    @Test
    fun pagesThatCannotBeUsedAreSkippedWithAReason() = runBlocking<Unit> {
        val urls = listOf("u1", "u2", "u3", "u4", "u5", "u6").map { "https://x.example/$it" }
        search.answers[question] = urls
        fetcher.pages[urls[0]] = UnsafeUrlException("inside.example points to a private or reserved address.")
        fetcher.pages[urls[1]] = FetchException("The page answered with HTTP 404.")
        fetcher.pages[urls[2]] = page(urls[2], "Tiny", "x")
        fetcher.pages[urls[3]] = page("https://x.example/final", "Final", boil)
        fetcher.pages[urls[4]] = page("https://x.example/final", "Final again", boil)
        fetcher.pages[urls[5]] = IOException("connection reset")
        val budget = ResearchBudget(resultsPerQuery = 6, minPageChars = 10)

        val report = runner(ScriptedGateway("[]"), ScriptedGateway("[]", "Boil [1]."), budget).run(question)

        assertEquals(listOf("https://x.example/final"), report.pages.map { it.url })
        assertEquals(5, report.skipped.size)
        val reasons = report.skipped.associate { it.url to it.reason }
        assertTrue(reasons.getValue(urls[0]).startsWith("Not fetched: "))
        assertTrue(reasons.getValue(urls[1]).contains("404"))
        assertEquals("Too little readable text.", reasons.getValue(urls[2]))
        assertEquals("Same page as one already read.", reasons.getValue(urls[4]))
        assertEquals("Failed: connection reset", reasons.getValue(urls[5]))
        assertTrue(report.toText().contains("Not used (5)"))
    }

    @Test
    fun ifNothingCanBeReadNothingIsCreated() = runBlocking<Unit> {
        search.answers[question] = listOf("https://x.example/missing")
        val answerer = ScriptedGateway()

        val report = runner(ScriptedGateway("[]"), answerer).run(question)

        assertNull(report.notebookId)
        assertNull(report.answer)
        assertNull(report.noteId)
        assertEquals(1, report.skipped.size)
        assertTrue(report.notes.any { it.contains("No page could be read") })
        assertTrue(repo.listNotebooks(includeArchived = true).isEmpty())
        assertTrue(repo.listSources().isEmpty())
        assertTrue(answerer.requests.isEmpty())
        assertTrue(report.toText().startsWith("Research did not produce a report."))
    }

    @Test
    fun noSearchResultsAtAllIsSaidPlainly() = runBlocking<Unit> {
        val report = runner(ScriptedGateway("[]"), ScriptedGateway()).run(question)

        assertTrue(report.notes.contains("The searches returned no web addresses."))
        assertNull(report.notebookId)
    }

    @Test
    fun aFailingSearchIsNotedAndTheOthersGoOn() = runBlocking<Unit> {
        search.answers[question] = IOException("search down")
        search.answers["b"] = listOf("https://x.example/p")
        fetcher.pages["https://x.example/p"] = page("https://x.example/p", "P", boil)

        val report = runner(ScriptedGateway("""["b"]"""), ScriptedGateway("[]", "Boil [1].")).run(question)

        assertEquals(1, report.pages.size)
        assertTrue(report.notes.any { it.contains("Searching for") && it.contains("search down") })
    }

    @Test
    fun theTimeBudgetStopsReadingButTheReportIsStillWritten() = runBlocking<Unit> {
        var t = 0L
        val urls = listOf("https://x.example/1", "https://x.example/2", "https://x.example/3")
        search.answers[question] = urls
        urls.forEach { fetcher.pages[it] = page(it, it, boil) }
        fetcher.onFetch = { t += 100 }
        val budget = ResearchBudget(minPageChars = 10, deadlineMs = 150)

        val report = runner(ScriptedGateway("[]"), ScriptedGateway("[]", "Boil [1]."), budget, now = { t }).run(question)

        assertEquals(2, report.pages.size)
        assertEquals(2, fetcher.fetched.size)
        assertTrue(report.notes.any { it.contains("time budget") })
        assertNotNull(report.answer)
    }

    @Test
    fun theAttemptLimitStopsReading() = runBlocking<Unit> {
        val urls = (1..5).map { "https://x.example/$it" }
        search.answers[question] = urls
        val budget = ResearchBudget(maxPages = 2, maxFetchAttempts = 2, minPageChars = 10)

        val report = runner(ScriptedGateway("[]"), ScriptedGateway(), budget).run(question)

        assertEquals(2, fetcher.fetched.size)
        assertTrue(report.notes.any { it.contains("limit of 2 page attempts") })
    }

    @Test
    fun aReportThatCitesNothingIsNotSavedAsANoteButThePagesAreKept() = runBlocking<Unit> {
        search.answers[question] = listOf("https://x.example/p")
        fetcher.pages["https://x.example/p"] = page("https://x.example/p", "P", boil)

        val report = runner(ScriptedGateway("[]"), ScriptedGateway("[]", "I think it is boiled.")).run(question)

        assertNull(report.noteId)
        assertTrue(report.notes.any { it.contains("not saved as a note") })
        assertEquals(1, repo.listSources(report.notebookId).size)
        assertTrue(repo.listNotes(report.notebookId!!).isEmpty())
    }

    @Test
    fun ifWritingTheReportFailsThePagesStayAndTheNoteSaysSo() = runBlocking<Unit> {
        search.answers[question] = listOf("https://x.example/p")
        fetcher.pages["https://x.example/p"] = page("https://x.example/p", "P", boil)

        val report = runner(ScriptedGateway("[]"), ScriptedGateway("[]", IOException("router down"))).run(question)

        assertNull(report.answer)
        assertNull(report.noteId)
        assertTrue(report.notes.any { it.contains("Writing the report failed") && it.contains("router down") })
        assertEquals(1, report.pages.size)
        assertEquals(1, repo.listSources(report.notebookId).size)
    }

    @Test
    fun ifMeaningVectorsCannotBeMadeKeywordSearchStillAnswers() = runBlocking<Unit> {
        search.answers[question] = listOf("https://x.example/p")
        fetcher.pages["https://x.example/p"] = page("https://x.example/p", "P", boil)
        embedder.failWith = "no endpoint"

        val report = runner(ScriptedGateway("[]"), ScriptedGateway("[]", "Boil it [1].")).run(question)

        assertTrue(report.notes.any { it.contains("Meaning search could not be prepared") })
        assertTrue(report.answer!!.cited)
        assertNotNull(report.noteId)
    }

    @Test
    fun cancellingBeforeAnythingIsKeptLeavesNothingBehind() = runBlocking<Unit> {
        search.answers[question] = listOf("https://x.example/p")
        fetcher.pages["https://x.example/p"] = CancellationException("stopped")

        try {
            runner(ScriptedGateway("[]"), ScriptedGateway()).run(question)
            fail("the cancellation should reach the caller")
        } catch (e: CancellationException) {
            assertEquals("stopped", e.message)
        }
        assertTrue(repo.listNotebooks(includeArchived = true).isEmpty())
        assertTrue(repo.listSources().isEmpty())
    }

    @Test
    fun anExistingNotebookCanBeUsed() = runBlocking<Unit> {
        val mine = repo.createNotebook("Mine")
        search.answers[question] = listOf("https://x.example/p")
        fetcher.pages["https://x.example/p"] = page("https://x.example/p", "P", boil)

        val report = runner(ScriptedGateway("[]"), ScriptedGateway("[]", "Boil it [1].")).run(question, notebookId = mine.id)

        assertEquals(mine.id, report.notebookId)
        assertEquals(1, repo.listSources(mine.id).size)
        assertEquals(1, repo.listNotebooks().size)
    }

    @Test
    fun anUnknownNotebookIsRefusedBeforeAnyCall() = runBlocking<Unit> {
        val planner = ScriptedGateway()

        try {
            runner(planner, ScriptedGateway()).run(question, notebookId = "nope")
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertEquals("That notebook does not exist.", e.message)
        }
        assertTrue(planner.requests.isEmpty())
        assertTrue(search.queries.isEmpty())
    }

    @Test
    fun aBlankQuestionIsRefused() = runBlocking<Unit> {
        try {
            runner(ScriptedGateway(), ScriptedGateway()).run("   ")
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertEquals("Ask a research question.", e.message)
        }
    }

    @Test
    fun aPageSavedBeforeIsUpdatedNotCopied() = runBlocking<Unit> {
        val url = "https://x.example/p"
        search.answers[question] = listOf(url)
        fetcher.pages[url] = page(url, "P", boil)
        runner(ScriptedGateway("[]"), ScriptedGateway("[]", "Boil [1].")).run(question)
        val first = repo.listSources().single()

        fetcher.pages[url] = page(url, "P", "Penne cooks faster than spaghetti, about eleven minutes.")
        runner(ScriptedGateway("[]"), ScriptedGateway("[]", "Faster [1].")).run(question)

        val after = repo.listSources().single()
        assertEquals(first.id, after.id)
        assertTrue(repo.getSource(after.id)!!.content.startsWith("Penne cooks faster"))
        assertFalse(first.contentHash == after.contentHash)
    }

    @Test
    fun theBudgetRefusesNonsense() {
        for (make in listOf<() -> ResearchBudget>(
            { ResearchBudget(maxQueries = 0) },
            { ResearchBudget(resultsPerQuery = 0) },
            { ResearchBudget(maxPages = 0) },
            { ResearchBudget(maxPages = 5, maxFetchAttempts = 4) },
            { ResearchBudget(minPageChars = -1) },
            { ResearchBudget(deadlineMs = 0) },
        )) {
            try {
                make()
                fail("should have thrown")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
