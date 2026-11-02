package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The chat with a scripted model: calls come in the order rewrite (follow-ups only), plan, answer. */
class NotebookChatTest {
    private var clock = 1_000L
    private var counter = 0
    private var chunkCounter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository
    private lateinit var ctx: ContextRepository
    private lateinit var hybrid: HybridSearch
    private lateinit var notebook: Notebook
    private lateinit var cooking: Source
    private lateinit var session: ChatSession

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
        hybrid = HybridSearch(db, embedder, "fake-model")

        notebook = repo.createNotebook("Dinner")
        cooking = repo.addTextSource("Cooking", "Boil the spaghetti until tender.")
        val garden = repo.addTextSource("Garden", "Tomato plants need full sun.")
        listOf(cooking, garden).forEach {
            repo.attachSource(notebook.id, it.info.id)
            chunker.index(it.info.id)
        }
        embeds.embedPending()
        session = ctx.createSession(notebook.id)
    }

    @After
    fun tearDown() = db.close()

    private fun chat(gateway: ScriptedGateway, model: String? = null): NotebookChat {
        val asker = KnowledgeAsker(gateway, hybrid, repo, model, NotebookContextBuilder(repo, ctx))
        return NotebookChat(gateway, asker, ctx, model)
    }

    @Test
    fun theFirstMessageIsAnsweredWithoutRewriting() = runBlocking<Unit> {
        val gateway = ScriptedGateway("[]", "Boil it until tender [1].")

        val turn = chat(gateway).send(session.id, "  How do I cook spaghetti?  ")

        assertEquals(2, gateway.requests.size) // plan and answer, no rewrite
        assertNull(turn.askedAs)
        assertEquals("How do I cook spaghetti?", turn.question.body)
        assertEquals(ChatRole.USER, turn.question.role)
        assertEquals(1, turn.question.ordinal)
        assertEquals("Boil it until tender [1].", turn.reply.body)
        assertEquals(2, turn.reply.ordinal)
        assertEquals(1, turn.reply.citations.size)
        assertEquals(cooking.info.id, turn.reply.citations[0].sourceId)
        assertEquals(listOf(turn.question, turn.reply), ctx.listMessages(session.id))
        assertEquals("How do I cook spaghetti?", ctx.getSession(session.id)!!.title)
    }

    @Test
    fun aFollowUpIsRewrittenIntoAStandaloneQuestion() = runBlocking<Unit> {
        val gateway = ScriptedGateway(
            "[]", "Boil it until tender [1].", // first message
            "How long do I boil spaghetti?", "[]", "About ten minutes [1].", // follow-up: rewrite, plan, answer
        )
        val chat = chat(gateway)
        chat.send(session.id, "How do I cook spaghetti?")

        val turn = chat.send(session.id, "And how long?")

        val rewrite = gateway.requests[2]
        assertEquals(FollowUpRewriter.SYSTEM, rewrite.system)
        assertTrue(rewrite.prompt.contains("Person: How do I cook spaghetti?"))
        assertTrue(rewrite.prompt.contains("Assistant: Boil it until tender [1]."))
        assertTrue(rewrite.prompt.contains("Latest message: And how long?"))

        assertEquals("How long do I boil spaghetti?", turn.askedAs)
        assertEquals("How long do I boil spaghetti?", turn.answer.question)
        assertEquals("How long do I boil spaghetti?", turn.answer.queries.first())
        assertEquals("And how long?", turn.question.body)
        assertEquals(listOf(3, 4), listOf(turn.question.ordinal, turn.reply.ordinal))
        assertTrue(turn.reply.notes.contains("Understood as: How long do I boil spaghetti?"))
        assertEquals(4, ctx.listMessages(session.id).size)
    }

    @Test
    fun ifRewritingFailsTheMessageIsSearchedAsWritten() = runBlocking<Unit> {
        val gateway = ScriptedGateway("[]", "Boil it [1].", java.io.IOException("router down"), "[]", "Ten minutes [1].")
        val chat = chat(gateway)
        chat.send(session.id, "How do I cook spaghetti?")

        val turn = chat.send(session.id, "spaghetti timing")

        assertNull(turn.askedAs)
        assertEquals("spaghetti timing", turn.answer.question)
        assertTrue(turn.reply.notes.any { it.contains("Reading your message in context failed") && it.contains("router down") })
        assertEquals("Ten minutes [1].", turn.reply.body)
    }

    @Test
    fun anUnusableRewriteIsIgnoredWithANote() = runBlocking<Unit> {
        val gateway = ScriptedGateway("[]", "Boil it [1].", "Line one\nLine two", "[]", "Ten minutes [1].")
        val chat = chat(gateway)
        chat.send(session.id, "How do I cook spaghetti?")

        val turn = chat.send(session.id, "spaghetti timing")

        assertNull(turn.askedAs)
        assertEquals("spaghetti timing", turn.answer.question)
        assertTrue(turn.reply.notes.any { it.contains("Could not turn your message into a standalone question") })
    }

    @Test
    fun aFailedAnswerLeavesTheChatUnchanged() = runBlocking<Unit> {
        val gateway = ScriptedGateway("[]", java.io.IOException("router down"))

        try {
            chat(gateway).send(session.id, "How do I cook spaghetti?")
            fail("should have thrown")
        } catch (e: java.io.IOException) {
            assertEquals("router down", e.message)
        }

        assertTrue(ctx.listMessages(session.id).isEmpty())
        assertEquals(ContextRepository.DEFAULT_TITLE, ctx.getSession(session.id)!!.title)
    }

    @Test
    fun badInputIsRefusedBeforeTheModelIsCalled() = runBlocking<Unit> {
        val gateway = ScriptedGateway()
        val chat = chat(gateway)
        val cases = listOf(
            session.id to "   ",
            session.id to "x".repeat(NotebookChat.MAX_MESSAGE_CHARS + 1),
            "ghost" to "Hello?",
        )
        for ((id, text) in cases) {
            try {
                chat.send(id, text)
                fail("should have thrown for ${text.take(10)}")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun aNotebookLevelChangeAppliesToTheNextQuestion() = runBlocking<Unit> {
        ctx.setLevel(notebook.id, cooking.info.id, ContextLevel.OFF)
        val gateway = ScriptedGateway("[]")

        val turn = chat(gateway).send(session.id, "How do I cook spaghetti?")

        assertEquals(KnowledgeAsker.NOTHING_FOUND, turn.reply.body)
        assertTrue(turn.reply.citations.isEmpty())
        assertEquals(2, ctx.listMessages(session.id).size)
    }

    @Test
    fun aReplyCanBeSavedAsAnAiNote() = runBlocking<Unit> {
        val chat = chat(ScriptedGateway("[]", "Boil it until tender [1]."))
        val turn = chat.send(session.id, "How do I cook spaghetti?")

        val note = chat.saveReplyAsNote(turn.reply.id)

        assertEquals(NoteOrigin.AI, note.origin)
        assertEquals(notebook.id, note.notebookId)
        assertEquals("How do I cook spaghetti?", note.title)
        assertEquals("Boil it until tender [1].\n\nSources\n[1] Cooking, lines 1", note.body)
        assertEquals(listOf(cooking.info.id), repo.listCitedSources(note.id).map { it.id })
    }

    @Test
    fun onlyACitedReplyCanBeSaved() = runBlocking<Unit> {
        val chat = chat(ScriptedGateway("[]", "No idea."))
        val turn = chat.send(session.id, "How do I cook spaghetti?")

        for (id in listOf(turn.question.id, "ghost", turn.reply.id)) {
            try {
                chat.saveReplyAsNote(id)
                fail("should have thrown for $id")
            } catch (e: IllegalArgumentException) {
                // expected: a question, a missing message, a reply that cites nothing
            }
        }
        assertTrue(repo.listNotes(notebook.id).isEmpty())
    }
}
