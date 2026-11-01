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

/** Runs the real SQL against an in-memory SQLite, so the migrations and every query are exercised. */
class KnowledgeRepositoryTest {
    private var clock = 1_000L
    private var counter = 0
    private lateinit var db: JdbcSqlDatabase
    private lateinit var repo: KnowledgeRepository

    @Before
    fun setUp() {
        clock = 1_000L
        counter = 0
        db = JdbcSqlDatabase()
        repo = KnowledgeRepository(db, now = { clock++ }, newId = { "id${++counter}" })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun count(table: String) = db.execute("SELECT COUNT(*) FROM $table").rows[0][0]!!.toInt()

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("should have been refused")
        return ""
    }

    // ---- Schema ----

    @Test
    fun theTablesAreCreatedOnFirstUse() = runBlocking<Unit> {
        repo.listNotebooks()
        val tables = db.execute("SELECT name FROM sqlite_master WHERE type = 'table'").rows.map { it[0] }.toSet()
        assertTrue(tables.containsAll(setOf("notebooks", "sources", "notebook_sources", "notes", "note_sources", "schema_version")))
        assertEquals(KnowledgeSchema.migrations.size, KnowledgeSchema.migrator(db).currentVersion())
    }

    @Test
    fun aSecondRepositoryOnTheSameDatabaseIsFine() = runBlocking<Unit> {
        val nb = repo.createNotebook("Shared")
        val other = KnowledgeRepository(db)
        assertEquals(nb, other.getNotebook(nb.id))
    }

    @Test
    fun aDatabaseNewerThanTheAppIsRefused() = runBlocking<Unit> {
        KnowledgeSchema.migrator(db).migrate()
        db.execute("UPDATE schema_version SET version = 99 WHERE component = 'knowledge'")
        try {
            KnowledgeRepository(db).listNotebooks()
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Update the app"))
        }
    }

    // ---- Notebooks ----

    @Test
    fun aNotebookRoundTrips() = runBlocking<Unit> {
        val nb = repo.createNotebook("  Reading  ", " ideas to follow up ")
        assertEquals("Reading", nb.name)
        assertEquals("ideas to follow up", nb.description)
        assertFalse(nb.archived)
        assertEquals(nb, repo.getNotebook(nb.id))
        assertNull(repo.getNotebook("missing"))
    }

    @Test
    fun aNotebookNeedsAName() = runBlocking<Unit> {
        assertEquals("A notebook needs a name.", refused { repo.createNotebook("   ") })
    }

    @Test
    fun notebooksListNewestFirstAndArchivedOnesAreHidden() = runBlocking<Unit> {
        val a = repo.createNotebook("A")
        val b = repo.createNotebook("B")
        assertEquals(listOf(b.id, a.id), repo.listNotebooks().map { it.id })

        assertTrue(repo.setArchived(a.id, true))
        assertEquals(listOf(b.id), repo.listNotebooks().map { it.id })
        assertEquals(setOf(a.id, b.id), repo.listNotebooks(includeArchived = true).map { it.id }.toSet())
        assertTrue(repo.getNotebook(a.id)!!.archived)

        assertTrue(repo.setArchived(a.id, false))
        assertEquals(2, repo.listNotebooks().size)
        assertFalse(repo.setArchived("missing", true))
    }

    @Test
    fun aNotebookCanBeRenamed() = runBlocking<Unit> {
        val nb = repo.createNotebook("Old", "d")
        assertTrue(repo.updateNotebook(nb.id, " New ", " new description "))
        val after = repo.getNotebook(nb.id)!!
        assertEquals("New", after.name)
        assertEquals("new description", after.description)
        assertTrue(after.updatedAt > nb.updatedAt)
        assertEquals(nb.createdAt, after.createdAt)
        assertFalse(repo.updateNotebook("missing", "x", ""))
        assertEquals("A notebook needs a name.", refused { repo.updateNotebook(nb.id, " ", "") })
    }

    // ---- Sources ----

    @Test
    fun pastedTextIsASource() = runBlocking<Unit> {
        val s = repo.addTextSource("Meeting", "We agreed to ship on Friday.")
        assertEquals(SourceKind.TEXT, s.info.kind)
        assertNull(s.info.url)
        assertNull(s.info.fetchedAt)
        assertEquals(KnowledgeText.hash("We agreed to ship on Friday."), s.info.contentHash)
        assertEquals("We agreed to ship on Friday.".length.toLong(), s.info.characters)
        assertEquals(s, repo.getSource(s.info.id))
        assertNull(repo.getSource("missing"))
    }

    @Test
    fun aBlankTitleIsTakenFromTheFirstLine() = runBlocking<Unit> {
        val s = repo.addTextSource("  ", "\n\n  First line here  \nsecond line")
        assertEquals("First line here", s.info.title)
        assertEquals(s, repo.getSource(s.info.id))
    }

    @Test
    fun emptyTextIsRefused() = runBlocking<Unit> {
        assertEquals("There is no text to save.", refused { repo.addTextSource("t", "  \n ") })
    }

    @Test
    fun theSameTextCanBeAddedTwice() = runBlocking<Unit> {
        repo.addTextSource("One", "same")
        repo.addTextSource("Two", "same")
        assertEquals(2, repo.listSources().size)
    }

    @Test
    fun characterCountMatchesWhatTheDatabaseSays() = runBlocking<Unit> {
        val s = repo.addTextSource("Emoji", "hi 😀")
        assertEquals(4L, s.info.characters)
        assertEquals(s, repo.getSource(s.info.id))
    }

    @Test
    fun aWebPageIsSavedUnderOneNormalisedAddress() = runBlocking<Unit> {
        val first = repo.saveWebSource("HTTPS://Example.com/Page#top", "Page", "old text", fetchedAt = 2_000L)
        assertEquals(SourceKind.WEB, first.info.kind)
        assertEquals("https://example.com/Page", first.info.url)
        assertEquals(2_000L, first.info.fetchedAt)
        assertEquals(first, repo.getSource(first.info.id))
    }

    @Test
    fun savingAPageAgainUpdatesTheSameSource() = runBlocking<Unit> {
        val first = repo.saveWebSource("https://example.com/Page", "Page", "old text", fetchedAt = 2_000L)
        val again = repo.saveWebSource("https://EXAMPLE.com/Page#section", "Page v2", "new text", fetchedAt = 5_000L)

        assertEquals(first.info.id, again.info.id)
        assertEquals(first.info.createdAt, again.info.createdAt)
        assertTrue(again.info.updatedAt > first.info.updatedAt)
        assertEquals(5_000L, again.info.fetchedAt)
        assertEquals(1, repo.listSources().size)
        assertEquals(again, repo.getSource(first.info.id))
        assertEquals("new text", repo.getSource(first.info.id)!!.content)
    }

    @Test
    fun aBlankPageTitleFallsBackToTheAddress() = runBlocking<Unit> {
        val s = repo.saveWebSource("https://example.com/a", " ", "text")
        assertEquals("https://example.com/a", s.info.title)
    }

    @Test
    fun badWebInputIsRefused() = runBlocking<Unit> {
        assertTrue(refused { repo.saveWebSource("ftp://example.com", "t", "text") }.contains("http"))
        assertTrue(refused { repo.saveWebSource("https://u:p@example.com", "t", "text") }.contains("username and password"))
        assertEquals("The page has no text to save.", refused { repo.saveWebSource("https://example.com", "t", "  ") })
        assertEquals(0, repo.listSources().size)
    }

    @Test
    fun theLibraryListReportsLengthInsteadOfText() = runBlocking<Unit> {
        repo.addTextSource("Long", "x".repeat(10_000))
        val info = repo.listSources().single()
        assertEquals(10_000L, info.characters)
    }

    // ---- Linking sources to notebooks ----

    @Test
    fun oneSourceCanBelongToManyNotebooks() = runBlocking<Unit> {
        val nb1 = repo.createNotebook("One")
        val nb2 = repo.createNotebook("Two")
        val shared = repo.addTextSource("Shared", "text")
        val onlyInOne = repo.addTextSource("Only one", "other")

        assertTrue(repo.attachSource(nb1.id, shared.info.id))
        assertTrue(repo.attachSource(nb2.id, shared.info.id))
        assertTrue(repo.attachSource(nb1.id, onlyInOne.info.id))

        assertEquals(setOf(shared.info.id, onlyInOne.info.id), repo.listSources(nb1.id).map { it.id }.toSet())
        assertEquals(listOf(shared.info.id), repo.listSources(nb2.id).map { it.id })
        assertEquals(2, repo.listSources().size)
    }

    @Test
    fun attachingTwiceOrToSomethingMissingChangesNothing() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val s = repo.addTextSource("S", "text")
        assertTrue(repo.attachSource(nb.id, s.info.id))
        assertFalse(repo.attachSource(nb.id, s.info.id))
        assertFalse(repo.attachSource("missing", s.info.id))
        assertFalse(repo.attachSource(nb.id, "missing"))
        assertEquals(1, count("notebook_sources"))
    }

    @Test
    fun detachingKeepsTheSourceInTheLibrary() = runBlocking<Unit> {
        val nb1 = repo.createNotebook("One")
        val nb2 = repo.createNotebook("Two")
        val s = repo.addTextSource("S", "text")
        repo.attachSource(nb1.id, s.info.id)
        repo.attachSource(nb2.id, s.info.id)

        assertTrue(repo.detachSource(nb1.id, s.info.id))
        assertTrue(repo.listSources(nb1.id).isEmpty())
        assertEquals(1, repo.listSources(nb2.id).size)
        assertNotNull(repo.getSource(s.info.id))
        assertFalse(repo.detachSource(nb1.id, s.info.id))
    }

    // ---- Notes ----

    @Test
    fun aNoteCitesSources() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val s1 = repo.addTextSource("Beta", "one")
        val s2 = repo.addTextSource("Alpha", "two")

        val note = repo.addNote(nb.id, NoteOrigin.AI, " Summary ", "The body.", listOf(s1.info.id, s1.info.id, s2.info.id))

        assertEquals("Summary", note.title)
        assertEquals(NoteOrigin.AI, note.origin)
        assertEquals(note, repo.getNote(note.id))
        assertEquals(listOf("Alpha", "Beta"), repo.listCitedSources(note.id).map { it.title })
        assertEquals(2, count("note_sources"))
        assertNull(repo.getNote("missing"))
    }

    @Test
    fun aNoteWithNoCitationsIsFine() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val note = repo.addNote(nb.id, NoteOrigin.HUMAN, "", "Just a thought")
        assertEquals("", note.title)
        assertTrue(repo.listCitedSources(note.id).isEmpty())
    }

    @Test
    fun aNoteNeedsAnExistingNotebookAndSources() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val s = repo.addTextSource("S", "text")

        assertEquals("That notebook does not exist.", refused { repo.addNote("missing", NoteOrigin.HUMAN, "t", "b") })
        assertEquals(
            "A cited source does not exist.",
            refused { repo.addNote(nb.id, NoteOrigin.AI, "t", "b", listOf(s.info.id, "missing")) },
        )
        assertEquals("A note needs some text.", refused { repo.addNote(nb.id, NoteOrigin.HUMAN, "t", "  ") })

        assertEquals(0, count("notes"))
        assertEquals(0, count("note_sources"))
    }

    @Test
    fun notesCanBeFilteredByOrigin() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val mine = repo.addNote(nb.id, NoteOrigin.HUMAN, "Mine", "a")
        val ai = repo.addNote(nb.id, NoteOrigin.AI, "AI", "b")
        val imported = repo.addNote(nb.id, NoteOrigin.IMPORTED, "Imported", "c")
        repo.addNote(repo.createNotebook("Other").id, NoteOrigin.HUMAN, "Elsewhere", "d")

        assertEquals(listOf(imported.id, ai.id, mine.id), repo.listNotes(nb.id).map { it.id })
        assertEquals(listOf(ai.id), repo.listNotes(nb.id, NoteOrigin.AI).map { it.id })
    }

    @Test
    fun editingANoteKeepsItsOrigin() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val note = repo.addNote(nb.id, NoteOrigin.AI, "Old", "old body")

        assertTrue(repo.updateNote(note.id, " New ", "new body"))
        val after = repo.getNote(note.id)!!
        assertEquals("New", after.title)
        assertEquals("new body", after.body)
        assertEquals(NoteOrigin.AI, after.origin)
        assertTrue(after.updatedAt > note.updatedAt)
        assertFalse(repo.updateNote("missing", "t", "b"))
        assertEquals("A note needs some text.", refused { repo.updateNote(note.id, "t", " ") })
    }

    @Test
    fun citationsCanBeAddedAndRemovedLater() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val s = repo.addTextSource("S", "text")
        val note = repo.addNote(nb.id, NoteOrigin.AI, "t", "b")

        assertTrue(repo.citeSource(note.id, s.info.id))
        assertFalse(repo.citeSource(note.id, s.info.id))
        assertFalse(repo.citeSource("missing", s.info.id))
        assertFalse(repo.citeSource(note.id, "missing"))
        assertEquals(listOf(s.info.id), repo.listCitedSources(note.id).map { it.id })

        assertTrue(repo.removeCitation(note.id, s.info.id))
        assertFalse(repo.removeCitation(note.id, s.info.id))
        assertTrue(repo.listCitedSources(note.id).isEmpty())
        assertNotNull(repo.getSource(s.info.id))
    }

    @Test
    fun deletingANoteRemovesItsCitationsOnly() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val s = repo.addTextSource("S", "text")
        val gone = repo.addNote(nb.id, NoteOrigin.AI, "gone", "b", listOf(s.info.id))
        val kept = repo.addNote(nb.id, NoteOrigin.AI, "kept", "b", listOf(s.info.id))

        assertTrue(repo.deleteNote(gone.id))
        assertNull(repo.getNote(gone.id))
        assertNotNull(repo.getNote(kept.id))
        assertEquals(1, count("note_sources"))
        assertNotNull(repo.getSource(s.info.id))
        assertFalse(repo.deleteNote(gone.id))
    }

    // ---- Deleting ----

    @Test
    fun deletingANotebookRemovesItsNotesButNotTheLibrary() = runBlocking<Unit> {
        val doomed = repo.createNotebook("Doomed")
        val other = repo.createNotebook("Other")
        val s = repo.addTextSource("S", "text")
        repo.attachSource(doomed.id, s.info.id)
        repo.attachSource(other.id, s.info.id)
        repo.addNote(doomed.id, NoteOrigin.HUMAN, "a", "a", listOf(s.info.id))
        repo.addNote(doomed.id, NoteOrigin.AI, "b", "b", listOf(s.info.id))
        val survivor = repo.addNote(other.id, NoteOrigin.HUMAN, "c", "c", listOf(s.info.id))

        assertTrue(repo.deleteNotebook(doomed.id))

        assertNull(repo.getNotebook(doomed.id))
        assertEquals(listOf(survivor.id), repo.listNotes(other.id).map { it.id })
        assertEquals(1, count("notes"))
        assertEquals(1, count("note_sources"))
        assertEquals(1, count("notebook_sources"))
        assertNotNull(repo.getSource(s.info.id))
        assertFalse(repo.deleteNotebook(doomed.id))
    }

    @Test
    fun deletingASourceRemovesItEverywhereButKeepsTheNotes() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        val doomed = repo.addTextSource("Doomed", "text")
        val other = repo.addTextSource("Other", "text 2")
        repo.attachSource(nb.id, doomed.info.id)
        repo.attachSource(nb.id, other.info.id)
        val note = repo.addNote(nb.id, NoteOrigin.AI, "t", "b", listOf(doomed.info.id, other.info.id))

        assertTrue(repo.deleteSource(doomed.info.id))

        assertNull(repo.getSource(doomed.info.id))
        assertEquals(listOf(other.info.id), repo.listSources(nb.id).map { it.id })
        assertEquals(listOf(other.info.id), repo.listCitedSources(note.id).map { it.id })
        assertNotNull(repo.getNote(note.id))
        assertFalse(repo.deleteSource(doomed.info.id))
    }

    // ---- The evidence rule ----

    @Test
    fun anAiNoteIsNeverEvidenceOnItsOwn() {
        fun note(origin: NoteOrigin) = Note("id", "nb", origin, "t", "b", 1, 1)
        assertFalse(note(NoteOrigin.AI).isEvidenceOnItsOwn)
        assertTrue(note(NoteOrigin.HUMAN).isEvidenceOnItsOwn)
        assertTrue(note(NoteOrigin.IMPORTED).isEvidenceOnItsOwn)
    }

    // ---- Several statements as one unit ----

    @Test
    fun aFailedTransactionLeavesNothingBehind() = runBlocking<Unit> {
        val nb = repo.createNotebook("N")
        try {
            db.transaction(
                listOf(
                    Stmt("INSERT INTO notes (id, notebook_id, origin, title, body, created_at, updated_at) VALUES ('x', ?, 'AI', 't', 'b', 1, 1)", listOf(nb.id)),
                    Stmt("INSERT INTO notes (id, notebook_id, origin, title, body, created_at, updated_at) VALUES ('y', ?, 'ROBOT', 't', 'b', 1, 1)", listOf(nb.id)),
                ),
            )
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.isNotEmpty())
        }
        assertEquals(0, count("notes"))
    }
}
