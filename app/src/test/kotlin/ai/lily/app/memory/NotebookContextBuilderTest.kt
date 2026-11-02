package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotebookContextBuilderTest {
    private var clock = 1_000L
    private var counter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var ctx: ContextRepository
    private lateinit var builder: NotebookContextBuilder

    @Before
    fun setUp() {
        clock = 1_000L
        counter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "s${++counter}" })
        ctx = ContextRepository(db, now = { clock++ }, newId = { "x${++counter}" })
        builder = NotebookContextBuilder(repo, ctx)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun linked(nb: Notebook, title: String, text: String, level: ContextLevel = ContextLevel.FULL): Source {
        val s = repo.addTextSource(title, text)
        repo.attachSource(nb.id, s.info.id)
        ctx.setLevel(nb.id, s.info.id, level)
        return s
    }

    private suspend fun insight(s: Source, body: String, prompt: SavedPrompt = BuiltinPrompts.SUMMARY) =
        ctx.saveInsight(s.info.id, prompt, s.info.contentHash, body, false, null)

    @Test
    fun theReportSizesEachSourceAndWhatReachesTheModel() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val text = "Boil the spaghetti until tender."
        val cooking = linked(nb, "Cooking", text)
        val garden = linked(nb, "Garden", "Tomato plants need full sun.", ContextLevel.INSIGHTS)
        linked(nb, "Zoo", "The fox and the dog live here.", ContextLevel.OFF)
        insight(garden, "Tomatoes need sun.")

        val report = builder.report(nb.id)

        val byTitle = report.lines.associateBy { it.source.title }
        assertEquals(ContextLevel.FULL, byTitle["Cooking"]!!.level)
        assertEquals(ContextLevel.INSIGHTS, byTitle["Garden"]!!.level)
        assertEquals(ContextLevel.OFF, byTitle["Zoo"]!!.level)
        assertEquals(KnowledgeTokens.estimate(text), byTitle["Cooking"]!!.textTokens)
        assertEquals(1, byTitle["Garden"]!!.freshInsights)
        assertEquals(0, byTitle["Garden"]!!.staleInsights)
        assertEquals(KnowledgeTokens.estimate("Tomatoes need sun."), byTitle["Garden"]!!.insightTokens)

        assertEquals(KnowledgeTokens.estimate(text), report.searchableTokens)
        assertEquals(KnowledgeTokens.estimate("Tomatoes need sun."), report.sentInsightTokens)
        assertEquals(report.searchableTokens + report.sentInsightTokens, report.perQuestionTokensAtMost)
        assertEquals(KnowledgeAsker.MAX_CONTEXT_CHARS / 4, report.excerptBudgetTokens)
        assertTrue(report.warnings.isEmpty())
        assertEquals(listOf(cooking.info.id), report.lines.filter { it.level == ContextLevel.FULL }.map { it.source.id })
    }

    @Test
    fun whatOneQuestionSendsIsCappedByTheExcerptBudget() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        linked(nb, "Big", "word ".repeat(20_000))

        val report = builder.report(nb.id)

        assertEquals(25_000, report.searchableTokens)
        assertEquals(report.excerptBudgetTokens, report.perQuestionTokensAtMost)
    }

    @Test
    fun insightsOnlyWithoutAnInsightIsWarnedAbout() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        linked(nb, "Garden", "Tomato plants need full sun.", ContextLevel.INSIGHTS)

        val report = builder.report(nb.id)

        assertTrue(report.warnings.any { it.contains("\"Garden\"") && it.contains("no up-to-date insight") })
        assertTrue(report.warnings.any { it.contains("Nothing in this notebook can reach the model") })
        assertEquals(0, report.perQuestionTokensAtMost)
    }

    @Test
    fun aStaleInsightIsNotSentAndIsWarnedAbout() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val v1 = repo.saveWebSource("https://example.com/g", "Garden page", "Tomato plants need full sun.")
        repo.attachSource(nb.id, v1.info.id)
        ctx.setLevel(nb.id, v1.info.id, ContextLevel.INSIGHTS)
        insight(v1, "Tomatoes need sun.")
        assertEquals(1, builder.insightsFor(nb.id).items.size)

        repo.saveWebSource("https://example.com/g", "Garden page", "Completely different text.")

        assertTrue(builder.insightsFor(nb.id).items.isEmpty())
        val line = builder.report(nb.id).lines.single()
        assertEquals(0, line.freshInsights)
        assertEquals(1, line.staleInsights)
        assertTrue(builder.report(nb.id).warnings.any { it.contains("out-of-date") })
    }

    @Test
    fun onlyInsightsOnlySourcesSendInsights() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val full = linked(nb, "Full", "full text")
        val off = linked(nb, "Off", "off text", ContextLevel.OFF)
        val only = linked(nb, "Only", "only text", ContextLevel.INSIGHTS)
        insight(full, "from full")
        insight(off, "from off")
        insight(only, "from only")

        val selection = builder.insightsFor(nb.id)

        assertEquals(listOf("from only"), selection.items.map { it.text })
        assertEquals(only.info.id, selection.items.single().source.id)
        assertEquals(0, selection.omitted)
    }

    @Test
    fun insightsShareABudgetAndEachIsClipped() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        repeat(6) { i -> insight(linked(nb, "S$i", "text $i", ContextLevel.INSIGHTS), "x".repeat(1_500)) }

        val selection = builder.insightsFor(nb.id)

        assertEquals(4, selection.items.size)
        assertEquals(2, selection.omitted)
        assertTrue(builder.report(nb.id).warnings.any { it.contains("2 insight(s) do not fit") })

        val nb2 = repo.createNotebook("Nb2")
        insight(linked(nb2, "Long", "text", ContextLevel.INSIGHTS), "y".repeat(5_000))
        val clipped = builder.insightsFor(nb2.id).items.single().text
        assertEquals(NotebookContextBuilder.INSIGHT_MAX_CHARS, clipped.length)
        assertTrue(clipped.endsWith("\u2026"))
    }

    @Test
    fun aNotebookWithNothingGivesAnEmptyReport() = runBlocking<Unit> {
        val nb = repo.createNotebook("Empty")
        val report = builder.report(nb.id)
        assertTrue(report.lines.isEmpty())
        assertEquals(0, report.perQuestionTokensAtMost)
        assertTrue(builder.insightsFor(nb.id).items.isEmpty())
    }
}
