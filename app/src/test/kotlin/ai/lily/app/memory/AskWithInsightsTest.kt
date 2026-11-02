package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** KnowledgeAsker with context levels and insights. Phase 6 behaviour without insights is covered by KnowledgeAskerTest. */
class AskWithInsightsTest {
    private var clock = 1_000L
    private var counter = 0
    private var chunkCounter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var ctx: ContextRepository
    private lateinit var chunker: KnowledgeIndexer
    private lateinit var embeds: EmbeddingIndexer
    private lateinit var hybrid: HybridSearch
    private lateinit var builder: NotebookContextBuilder

    @Before
    fun setUp() {
        clock = 1_000L
        counter = 0
        chunkCounter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "s${++counter}" })
        ctx = ContextRepository(db, now = { clock++ }, newId = { "x${++counter}" })
        chunker = KnowledgeIndexer(db, now = { clock++ }, newId = { "c${++chunkCounter}" })
        val embedder = ConceptEmbedder()
        embeds = EmbeddingIndexer(db, embedder, "fake-model", now = { clock++ })
        hybrid = HybridSearch(db, embedder, "fake-model")
        builder = NotebookContextBuilder(repo, ctx)
    }

    @After
    fun tearDown() = db.close()

    private fun asker(gateway: ScriptedGateway) = KnowledgeAsker(gateway, hybrid, repo, null, builder)

    private suspend fun add(nb: Notebook, title: String, text: String, level: ContextLevel = ContextLevel.FULL): Source {
        val s = repo.addTextSource(title, text)
        chunker.index(s.info.id)
        repo.attachSource(nb.id, s.info.id)
        ctx.setLevel(nb.id, s.info.id, level)
        embeds.embedPending()
        return s
    }

    private suspend fun insight(s: Source, body: String) =
        ctx.saveInsight(s.info.id, BuiltinPrompts.SUMMARY, s.info.contentHash, body, false, null)

    @Test
    fun insightsFollowTheExcerptsAndAreNumberedOnFromThem() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val cooking = add(nb, "Cooking", "Boil the spaghetti until tender.")
        val garden = add(nb, "Garden", "Tomato plants need full sun.", ContextLevel.INSIGHTS)
        val saved = insight(garden, "Garden notes: tomatoes love sun.")
        val gateway = ScriptedGateway("[]", "Boil it [1]. Tomatoes want sun [2].")

        val answer = asker(gateway).ask("spaghetti", nb.id)

        val prompt = gateway.requests[1].prompt
        assertTrue(prompt.contains("<excerpt n=\"1\" source=\"Cooking\" lines=\"1\">"))
        assertTrue(prompt.contains("AI-written insights"))
        assertTrue(prompt.contains("<insight n=\"2\" source=\"Garden\" analysis=\"Summary\" ai-written=\"true\">"))
        assertTrue(prompt.contains("Garden notes: tomatoes love sun."))
        assertTrue(prompt.indexOf("<excerpt") < prompt.indexOf("<insight"))
        assertTrue(prompt.endsWith("Question: spaghetti"))
        assertTrue(gateway.requests[1].system!!.contains("<insight>"))

        assertEquals(1, answer.retrieved)
        assertEquals(1, answer.insightsShown)
        assertEquals(2, answer.citations.size)
        val text = answer.citations[0]
        assertEquals(CitationBasis.TEXT, text.basis)
        assertEquals(cooking.info.id, text.sourceId)
        val ai = answer.citations[1]
        assertEquals(CitationBasis.INSIGHT, ai.basis)
        assertEquals(garden.info.id, ai.sourceId)
        assertEquals(saved.id, ai.insightId)
        assertEquals("", ai.chunkId)
        assertEquals(0, ai.startLine)
        assertEquals("Garden notes: tomatoes love sun.", ai.excerpt)
        assertTrue(answer.groundedInText)
    }

    @Test
    fun aSourceThatIsNotInsightsOnlyContributesNoInsight() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val cooking = add(nb, "Cooking", "Boil the spaghetti until tender.")
        insight(cooking, "An insight about cooking.")
        val gateway = ScriptedGateway("[]", "Boil it [1].")

        val answer = asker(gateway).ask("spaghetti", nb.id)

        val prompt = gateway.requests[1].prompt
        assertFalse(prompt.contains("<insight"))
        assertFalse(prompt.contains("AI-written insights"))
        assertFalse(gateway.requests[1].system!!.contains("<insight>"))
        assertEquals(KnowledgeAsker.ANSWER_SYSTEM, gateway.requests[1].system)
        assertEquals(0, answer.insightsShown)
    }

    @Test
    fun anAnswerThatRestsOnlyOnInsightsIsFlaggedAndNotSaved() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val garden = add(nb, "Garden", "Tomato plants need full sun.", ContextLevel.INSIGHTS)
        insight(garden, "Tomatoes love sun.")
        val asker = asker(ScriptedGateway("[]", "Tomatoes love sun [1]."))

        val answer = asker.ask("tomato", nb.id)

        assertEquals(0, answer.retrieved)
        assertEquals(1, answer.insightsShown)
        assertTrue(answer.cited)
        assertFalse(answer.groundedInText)
        assertTrue(answer.notes.any { it.contains("rests only on AI-written insights") })
        try {
            asker.saveAsNote(nb.id, answer)
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("insights"))
        }
        assertTrue(repo.listNotes(nb.id).isEmpty())
    }

    @Test
    fun aStaleInsightIsNotSentSoNothingIsFound() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val v1 = repo.saveWebSource("https://example.com/g", "Garden page", "Tomato plants need full sun.")
        chunker.index(v1.info.id)
        repo.attachSource(nb.id, v1.info.id)
        ctx.setLevel(nb.id, v1.info.id, ContextLevel.INSIGHTS)
        insight(v1, "Tomatoes love sun.")
        repo.saveWebSource("https://example.com/g", "Garden page", "Completely different text.")
        val gateway = ScriptedGateway("[]")

        val answer = asker(gateway).ask("tomato", nb.id)

        assertEquals(KnowledgeAsker.NOTHING_FOUND, answer.text)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun askingTheWholeLibraryIgnoresLevelsAndSendsNoInsights() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val garden = add(nb, "Garden", "Tomato plants need full sun.", ContextLevel.INSIGHTS)
        insight(garden, "Tomatoes love sun.")
        val gateway = ScriptedGateway("[]", "Sun [1].")

        val answer = asker(gateway).ask("tomato")

        assertEquals(1, answer.retrieved)
        assertEquals(0, answer.insightsShown)
        assertFalse(gateway.requests[1].prompt.contains("<insight"))
    }

    @Test
    fun aSavedNoteCitesOnlySourcesWhoseTextWasUsed() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val cooking = add(nb, "Cooking", "Boil the spaghetti until tender.")
        val garden = add(nb, "Garden", "Tomato plants need full sun.", ContextLevel.INSIGHTS)
        insight(garden, "Tomatoes love sun.")
        val asker = asker(ScriptedGateway("[]", "Boil it [1]. Sun matters [2]."))
        val answer = asker.ask("spaghetti", nb.id)

        val note = asker.saveAsNote(nb.id, answer)

        assertEquals(NoteOrigin.AI, note.origin)
        assertEquals(
            "Boil it [1]. Sun matters [2].\n\nSources\n[1] Cooking, lines 1\n[2] Garden, AI-written insight",
            note.body,
        )
        assertEquals(listOf(cooking.info.id), repo.listCitedSources(note.id).map { it.id })
    }

    @Test
    fun withoutABuilderNothingChangesFromPhaseSix() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val garden = add(nb, "Garden", "Tomato plants need full sun.", ContextLevel.INSIGHTS)
        insight(garden, "Tomatoes love sun.")
        val gateway = ScriptedGateway("[]")

        val answer = KnowledgeAsker(gateway, hybrid, repo).ask("tomato", nb.id)

        assertEquals(KnowledgeAsker.NOTHING_FOUND, answer.text)
        assertNull(answer.routedVia)
    }
}
