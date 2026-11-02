package ai.lily.app.memory

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

/** Real SQL in memory, through the real migrations. */
class ContextRepositoryTest {
    private var clock = 1_000L
    private var counter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var ctx: ContextRepository

    @Before
    fun setUp() {
        clock = 1_000L
        counter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "s${++counter}" })
        ctx = ContextRepository(db, now = { clock++ }, newId = { "x${++counter}" })
    }

    @After
    fun tearDown() = db.close()

    private fun citation(n: Int, basis: CitationBasis = CitationBasis.TEXT, url: String? = null) = AskCitation(
        number = n, chunkId = if (basis == CitationBasis.TEXT) "c$n" else "", sourceId = "src$n", sourceTitle = "Title $n",
        sourceKind = if (url == null) SourceKind.TEXT else SourceKind.WEB, url = url, startLine = n, endLine = n + 2,
        excerpt = "excerpt $n", basis = basis, insightId = if (basis == CitationBasis.INSIGHT) "i$n" else null,
    )

    // ---- levels ----

    @Test
    fun aLinkedSourceIsFullByDefaultAndTheLevelCanBeChanged() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val src = repo.addTextSource("A", "text")
        repo.attachSource(nb.id, src.info.id)

        assertEquals(ContextLevel.FULL, ctx.levelOf(nb.id, src.info.id))
        assertTrue(ctx.setLevel(nb.id, src.info.id, ContextLevel.OFF))
        assertEquals(ContextLevel.OFF, ctx.levelOf(nb.id, src.info.id))
        assertTrue(ctx.setLevel(nb.id, src.info.id, ContextLevel.INSIGHTS))
        assertEquals(ContextLevel.INSIGHTS, ctx.levelOf(nb.id, src.info.id))
        assertEquals(mapOf(src.info.id to ContextLevel.INSIGHTS), ctx.levels(nb.id))
        assertTrue(ctx.setLevel(nb.id, src.info.id, ContextLevel.FULL))
        assertEquals(ContextLevel.FULL, ctx.levelOf(nb.id, src.info.id))
        assertTrue(ctx.levels(nb.id).isEmpty())
    }

    @Test
    fun aSourceThatIsNotLinkedHasNoLevelAndCannotGetOne() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val src = repo.addTextSource("A", "text")

        assertNull(ctx.levelOf(nb.id, src.info.id))
        assertFalse(ctx.setLevel(nb.id, src.info.id, ContextLevel.OFF))
        assertFalse(ctx.setLevel(nb.id, src.info.id, ContextLevel.FULL))
        assertTrue(ctx.levels(nb.id).isEmpty())
    }

    @Test
    fun theLevelBelongsToTheNotebookAndIsForgottenWhenUnlinked() = runBlocking<Unit> {
        val one = repo.createNotebook("One")
        val two = repo.createNotebook("Two")
        val src = repo.addTextSource("A", "text")
        repo.attachSource(one.id, src.info.id)
        repo.attachSource(two.id, src.info.id)
        ctx.setLevel(one.id, src.info.id, ContextLevel.OFF)

        assertEquals(ContextLevel.OFF, ctx.levelOf(one.id, src.info.id))
        assertEquals(ContextLevel.FULL, ctx.levelOf(two.id, src.info.id))

        repo.detachSource(one.id, src.info.id)
        repo.attachSource(one.id, src.info.id)
        assertEquals(ContextLevel.FULL, ctx.levelOf(one.id, src.info.id))
    }

    @Test
    fun deletingASourceOrANotebookRemovesItsLevels() = runBlocking<Unit> {
        val one = repo.createNotebook("One")
        val two = repo.createNotebook("Two")
        val a = repo.addTextSource("A", "a text")
        val b = repo.addTextSource("B", "b text")
        listOf(one, two).forEach { repo.attachSource(it.id, a.info.id) }
        repo.attachSource(one.id, b.info.id)
        ctx.setLevel(one.id, a.info.id, ContextLevel.OFF)
        ctx.setLevel(two.id, a.info.id, ContextLevel.INSIGHTS)
        ctx.setLevel(one.id, b.info.id, ContextLevel.OFF)

        repo.deleteNotebook(one.id)
        assertTrue(ctx.levels(one.id).isEmpty())
        assertEquals(1, ctx.levels(two.id).size)

        repo.deleteSource(a.info.id)
        assertTrue(ctx.levels(two.id).isEmpty())
    }

    // ---- prompts ----

    @Test
    fun builtInPromptsComeFirstThenTheOnesYouSave() = runBlocking<Unit> {
        val mine = ctx.savePrompt("  Zebra facts ", "  List the stripes.  ")
        assertEquals("Zebra facts", mine.title)
        assertEquals("List the stripes.", mine.prompt)
        assertFalse(mine.builtin)

        val all = ctx.listPrompts()
        assertEquals(BuiltinPrompts.all.map { it.id } + mine.id, all.map { it.id })
        assertEquals(listOf("Summary", "Key claims"), all.take(2).map { it.title })
        assertEquals(mine, ctx.getPrompt(mine.id))
        assertEquals(BuiltinPrompts.SUMMARY, ctx.getPrompt(BuiltinPrompts.SUMMARY.id))
        assertNull(ctx.getPrompt("nope"))
    }

    @Test
    fun promptTitlesMustBeUniqueEvenAgainstBuiltInsAndIgnoringCase() = runBlocking<Unit> {
        ctx.savePrompt("Mine", "one")
        for (title in listOf("mine", "SUMMARY")) {
            try {
                ctx.savePrompt(title, "two")
                fail("should have thrown for $title")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("already a prompt"))
            }
        }
        val other = ctx.savePrompt("Other", "three")
        try {
            ctx.updatePrompt(other.id, "MINE", "three")
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("already a prompt"))
        }
        assertTrue(ctx.updatePrompt(other.id, "Other", "changed"))
        assertEquals("changed", ctx.getPrompt(other.id)!!.prompt)
    }

    @Test
    fun badPromptsAreRefused() = runBlocking<Unit> {
        for ((title, text) in listOf("" to "x", "x" to "  ", "t".repeat(81) to "x", "x" to "p".repeat(4001))) {
            try {
                ctx.savePrompt(title, text)
                fail("should have thrown")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertEquals(BuiltinPrompts.all.size, ctx.listPrompts().size)
    }

    @Test
    fun builtInPromptsCannotBeChangedOrDeleted() = runBlocking<Unit> {
        try {
            ctx.updatePrompt(BuiltinPrompts.SUMMARY.id, "New", "text")
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Built-in"))
        }
        try {
            ctx.deletePrompt(BuiltinPrompts.SUMMARY.id)
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Built-in"))
        }
    }

    // ---- insights ----

    @Test
    fun anInsightIsSavedReplacedAndListed() = runBlocking<Unit> {
        val src = repo.addTextSource("A", "some text")
        val first = ctx.saveInsight(src.info.id, BuiltinPrompts.SUMMARY, src.info.contentHash, "v1", false, "fake/router")
        assertFalse(first.stale)
        assertEquals("fake/router", first.routedVia)
        assertEquals(listOf(first), ctx.listInsights(src.info.id))

        val second = ctx.saveInsight(src.info.id, BuiltinPrompts.SUMMARY, src.info.contentHash, "v2", true, null)
        assertEquals(first.id, second.id)
        val listed = ctx.listInsights(src.info.id).single()
        assertEquals("v2", listed.body)
        assertTrue(listed.truncated)
        assertNull(listed.routedVia)

        ctx.saveInsight(src.info.id, BuiltinPrompts.KEY_CLAIMS, src.info.contentHash, "claims", false, null)
        assertEquals(listOf("Key claims", "Summary"), ctx.listInsights(src.info.id).map { it.promptTitle })
    }

    @Test
    fun anInsightForAMissingSourceIsRefused() = runBlocking<Unit> {
        try {
            ctx.saveInsight("ghost", BuiltinPrompts.SUMMARY, "hash", "text", false, null)
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("source"))
        }
        assertTrue(ctx.insightsFor(listOf("ghost")).isEmpty())
    }

    @Test
    fun anInsightGoesStaleWhenTheSourceTextChanges() = runBlocking<Unit> {
        val v1 = repo.saveWebSource("https://example.com/a", "Page", "first text")
        ctx.saveInsight(v1.info.id, BuiltinPrompts.SUMMARY, v1.info.contentHash, "about first", false, null)
        assertFalse(ctx.listInsights(v1.info.id).single().stale)

        val v2 = repo.saveWebSource("https://example.com/a", "Page", "second text")
        assertEquals(v1.info.id, v2.info.id)
        assertTrue(ctx.listInsights(v1.info.id).single().stale)

        ctx.saveInsight(v2.info.id, BuiltinPrompts.SUMMARY, v2.info.contentHash, "about second", false, null)
        assertFalse(ctx.listInsights(v1.info.id).single().stale)
    }

    @Test
    fun anInsightGoesStaleWhenItsPromptChanges() = runBlocking<Unit> {
        val src = repo.addTextSource("A", "text")
        val prompt = ctx.savePrompt("Mine", "Do it this way.")
        ctx.saveInsight(src.info.id, prompt, src.info.contentHash, "result", false, null)
        assertFalse(ctx.listInsights(src.info.id).single().stale)

        ctx.updatePrompt(prompt.id, "Mine", "Do it that way.")
        assertTrue(ctx.listInsights(src.info.id).single().stale)
    }

    @Test
    fun deletingAPromptOrASourceRemovesItsInsights() = runBlocking<Unit> {
        val a = repo.addTextSource("A", "a text")
        val b = repo.addTextSource("B", "b text")
        val prompt = ctx.savePrompt("Mine", "Do it.")
        ctx.saveInsight(a.info.id, prompt, a.info.contentHash, "1", false, null)
        ctx.saveInsight(a.info.id, BuiltinPrompts.SUMMARY, a.info.contentHash, "2", false, null)
        ctx.saveInsight(b.info.id, BuiltinPrompts.SUMMARY, b.info.contentHash, "3", false, null)

        assertTrue(ctx.deletePrompt(prompt.id))
        assertEquals(listOf(BuiltinPrompts.SUMMARY.id), ctx.listInsights(a.info.id).map { it.promptId })

        repo.deleteSource(a.info.id)
        assertTrue(ctx.listInsights(a.info.id).isEmpty())
        assertEquals(1, ctx.listInsights(b.info.id).size)

        val only = ctx.listInsights(b.info.id).single()
        assertTrue(ctx.deleteInsight(only.id))
        assertFalse(ctx.deleteInsight(only.id))
    }

    // ---- chats ----

    @Test
    fun aChatGetsItsTitleFromTheFirstQuestionOnly() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val session = ctx.createSession(nb.id)
        assertEquals(ContextRepository.DEFAULT_TITLE, session.title)

        ctx.appendExchange(session.id, "  How do I   cook spaghetti? ", "Boil it.", emptyList(), emptyList())
        assertEquals("How do I cook spaghetti?", ctx.getSession(session.id)!!.title)

        ctx.appendExchange(session.id, "Something else", "Reply.", emptyList(), emptyList())
        assertEquals("How do I cook spaghetti?", ctx.getSession(session.id)!!.title)
    }

    @Test
    fun aNamedChatKeepsItsName() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val session = ctx.createSession(nb.id, " Dinner plans ")
        ctx.appendExchange(session.id, "A question", "An answer", emptyList(), emptyList())
        assertEquals("Dinner plans", ctx.getSession(session.id)!!.title)
        assertTrue(ctx.renameSession(session.id, "Renamed"))
        assertEquals("Renamed", ctx.getSession(session.id)!!.title)
    }

    @Test
    fun messagesKeepTheirOrderCitationsAndNotes() = runBlocking<Unit> {
        val nb = repo.createNotebook("Nb")
        val session = ctx.createSession(nb.id)
        val cites = listOf(citation(1, url = "https://example.com/p"), citation(2, CitationBasis.INSIGHT))

        val (q1, r1) = ctx.appendExchange(session.id, "First?", "One [1][2].", cites, listOf("a note", "another"))
        val (q2, r2) = ctx.appendExchange(session.id, "Second?", "Two.", emptyList(), emptyList())

        assertEquals(listOf(1, 2, 3, 4), listOf(q1, r1, q2, r2).map { it.ordinal })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT), listOf(q1, r1, q2, r2).map { it.role })
        assertEquals(listOf(q1, r1, q2, r2), ctx.listMessages(session.id))
        assertEquals(cites, ctx.getMessage(r1.id)!!.citations)
        assertEquals(listOf("a note", "another"), r1.notes)
        assertTrue(q1.citations.isEmpty())
        assertEquals(listOf(q2, r2), ctx.recentMessages(session.id, 2))
        assertEquals(q2, ctx.messageAt(session.id, 3))
        assertNull(ctx.messageAt(session.id, 9))
    }

    @Test
    fun anExchangeForAMissingChatIsRefusedAndWritesNothing() = runBlocking<Unit> {
        try {
            ctx.appendExchange("ghost", "Q?", "A.", emptyList(), emptyList())
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("chat"))
        }
        assertTrue(ctx.listMessages("ghost").isEmpty())
    }

    @Test
    fun aChatNeedsAnExistingNotebook() = runBlocking<Unit> {
        try {
            ctx.createSession("ghost")
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("notebook"))
        }
    }

    @Test
    fun deletingAChatOrItsNotebookRemovesTheMessages() = runBlocking<Unit> {
        val keep = repo.createNotebook("Keep")
        val drop = repo.createNotebook("Drop")
        val kept = ctx.createSession(keep.id)
        val a = ctx.createSession(drop.id)
        val b = ctx.createSession(drop.id)
        listOf(kept, a, b).forEach { ctx.appendExchange(it.id, "Q?", "A.", emptyList(), emptyList()) }

        assertTrue(ctx.deleteSession(a.id))
        assertNull(ctx.getSession(a.id))
        assertTrue(ctx.listMessages(a.id).isEmpty())
        assertEquals(2, ctx.listMessages(b.id).size)

        repo.deleteNotebook(drop.id)
        assertTrue(ctx.listSessions(drop.id).isEmpty())
        assertTrue(ctx.listMessages(b.id).isEmpty())
        assertEquals(2, ctx.listMessages(kept.id).size)
        assertNotNull(ctx.getSession(kept.id))
    }

    // ---- codec ----

    @Test
    fun unreadableStoredJsonGivesEmptyLists() {
        assertTrue(ChatCodec.decodeCitations("not json").isEmpty())
        assertTrue(ChatCodec.decodeNotes("{").isEmpty())
        assertEquals(listOf("x"), ChatCodec.decodeNotes(ChatCodec.encodeNotes(listOf("x"))))
    }
}
