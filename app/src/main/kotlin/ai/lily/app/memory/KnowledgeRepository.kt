package ai.lily.app.memory

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Notebooks, the shared library of sources, and notes, stored in the knowledge database.
 *
 * - A source lives once in the library and can be linked to many notebooks. Removing it from a notebook keeps it in the library.
 * - A note belongs to one notebook and may cite sources. An AI note is labelled by its [NoteOrigin] and is not evidence on its own.
 * - Writes that touch several tables run as one transaction. The tables are created and upgraded on first use.
 * - The proven facts database is never read or written here.
 *
 * Methods that look something up return null or false when it is not there. Bad input throws [IllegalArgumentException].
 */
class KnowledgeRepository(
    private val db: SqlDatabase,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val migrator = KnowledgeSchema.migrator(db)
    private val gate = Mutex()
    private var isReady = false

    private suspend fun ensureReady() {
        if (isReady) return
        gate.withLock {
            if (!isReady) {
                migrator.migrate()
                isReady = true
            }
        }
    }

    // ---- Notebooks ----

    suspend fun createNotebook(name: String, description: String = ""): Notebook {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "A notebook needs a name." }
        val cleanDescription = description.trim()
        ensureReady()
        val id = newId()
        val t = now()
        db.execute(INSERT_NOTEBOOK, listOf(id, cleanName, cleanDescription, t, t))
        return Notebook(id, cleanName, cleanDescription, archived = false, createdAt = t, updatedAt = t)
    }

    suspend fun getNotebook(id: String): Notebook? {
        ensureReady()
        return db.execute(SELECT_NOTEBOOK, listOf(id)).rows.firstOrNull()?.let(::notebookOf)
    }

    /** Most recently changed first. Archived notebooks are left out unless asked for. */
    suspend fun listNotebooks(includeArchived: Boolean = false): List<Notebook> {
        ensureReady()
        val sql = if (includeArchived) LIST_NOTEBOOKS_ALL else LIST_NOTEBOOKS_ACTIVE
        return db.execute(sql).rows.map(::notebookOf)
    }

    suspend fun updateNotebook(id: String, name: String, description: String): Boolean {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "A notebook needs a name." }
        ensureReady()
        return db.execute(UPDATE_NOTEBOOK, listOf(cleanName, description.trim(), now(), id)).affectedRows > 0
    }

    suspend fun setArchived(id: String, archived: Boolean): Boolean {
        ensureReady()
        return db.execute(SET_ARCHIVED, listOf(if (archived) 1 else 0, now(), id)).affectedRows > 0
    }

    /** Deletes the notebook and its notes. Sources stay in the library. */
    suspend fun deleteNotebook(id: String): Boolean {
        ensureReady()
        val results = db.transaction(
            listOf(
                Stmt(DELETE_NOTEBOOK_NOTE_SOURCES, listOf(id)),
                Stmt(DELETE_NOTEBOOK_NOTES, listOf(id)),
                Stmt(DELETE_NOTEBOOK_LINKS, listOf(id)),
                Stmt(DELETE_NOTEBOOK, listOf(id)),
            ),
        )
        return results.last().affectedRows > 0
    }

    // ---- Sources (the shared library) ----

    /** Pasted text. If [title] is blank, the first line of the text is used. */
    suspend fun addTextSource(title: String, content: String): Source {
        require(content.isNotBlank()) { "There is no text to save." }
        val name = title.trim().ifEmpty { content.lineSequence().first { it.isNotBlank() }.trim().take(TITLE_FROM_TEXT) }
        ensureReady()
        val id = newId()
        val t = now()
        val hash = KnowledgeText.hash(content)
        db.execute(INSERT_TEXT_SOURCE, listOf(id, name, content, hash, t, t))
        val info = SourceInfo(id, SourceKind.TEXT, name, null, hash, KnowledgeText.characters(content), null, t, t)
        return Source(info, content)
    }

    /**
     * A web page whose text the caller has already fetched. Saving an address that is already in the library updates that source
     * (same id, same creation time) instead of adding a copy. [fetchedAt] defaults to now.
     */
    suspend fun saveWebSource(url: String, title: String, content: String, fetchedAt: Long? = null): Source {
        val address = KnowledgeText.normaliseUrl(url)
        require(content.isNotBlank()) { "The page has no text to save." }
        val name = title.trim().ifEmpty { address }
        ensureReady()
        val t = now()
        val fetched = fetchedAt ?: t
        val hash = KnowledgeText.hash(content)
        val results = db.transaction(
            listOf(
                Stmt(UPSERT_WEB_SOURCE, listOf(newId(), name, content, address, hash, fetched, t, t)),
                Stmt(SELECT_SOURCE_BY_URL, listOf(address)),
            ),
        )
        val row = results[1].rows.firstOrNull() ?: error("The page was saved but could not be read back.")
        val info = SourceInfo(
            id = row[0].orEmpty(),
            kind = SourceKind.WEB,
            title = name,
            url = address,
            contentHash = hash,
            characters = KnowledgeText.characters(content),
            fetchedAt = fetched,
            createdAt = row[1]?.toLongOrNull() ?: t,
            updatedAt = t,
        )
        return Source(info, content)
    }

    suspend fun getSource(id: String): Source? {
        ensureReady()
        val row = db.execute(SELECT_SOURCE, listOf(id)).rows.firstOrNull() ?: return null
        return Source(infoOf(row), row[9].orEmpty())
    }

    /** The whole library, or only the sources linked to [notebookId]. Without their text. */
    suspend fun listSources(notebookId: String? = null): List<SourceInfo> {
        ensureReady()
        val result = if (notebookId == null) db.execute(LIST_SOURCES) else db.execute(LIST_NOTEBOOK_SOURCES, listOf(notebookId))
        return result.rows.map(::infoOf)
    }

    /** Removes the source from the library, from every notebook and from every note's citations. Notes stay. */
    suspend fun deleteSource(id: String): Boolean {
        ensureReady()
        val results = db.transaction(
            listOf(
                Stmt(DELETE_SOURCE_CITATIONS, listOf(id)),
                Stmt(DELETE_SOURCE_LINKS, listOf(id)),
                Stmt(DELETE_SOURCE, listOf(id)),
            ),
        )
        return results.last().affectedRows > 0
    }

    /** Links a library source to a notebook. True if a new link was made; false if it was already linked or either one is missing. */
    suspend fun attachSource(notebookId: String, sourceId: String): Boolean {
        ensureReady()
        val args = listOf(notebookId, sourceId, now(), notebookId, sourceId)
        return db.execute(ATTACH_SOURCE, args).affectedRows > 0
    }

    /** Unlinks a source from a notebook. It stays in the library. */
    suspend fun detachSource(notebookId: String, sourceId: String): Boolean {
        ensureReady()
        return db.execute(DETACH_SOURCE, listOf(notebookId, sourceId)).affectedRows > 0
    }

    // ---- Notes ----

    /** Adds a note to a notebook, citing the given library sources. The note and its citations are written together or not at all. */
    suspend fun addNote(
        notebookId: String,
        origin: NoteOrigin,
        title: String,
        body: String,
        sourceIds: List<String> = emptyList(),
    ): Note {
        require(body.isNotBlank()) { "A note needs some text." }
        ensureReady()
        require(notebookExists(notebookId)) { "That notebook does not exist." }
        val cited = sourceIds.distinct()
        requireSourcesExist(cited)
        val id = newId()
        val t = now()
        val cleanTitle = title.trim()
        val statements = listOf(Stmt(INSERT_NOTE, listOf(id, notebookId, origin.name, cleanTitle, body, t, t))) +
            cited.map { Stmt(INSERT_NOTE_SOURCE, listOf(id, it)) }
        db.transaction(statements)
        return Note(id, notebookId, origin, cleanTitle, body, t, t)
    }

    suspend fun getNote(id: String): Note? {
        ensureReady()
        return db.execute(SELECT_NOTE, listOf(id)).rows.firstOrNull()?.let(::noteOf)
    }

    /** Most recently changed first, optionally only notes with the given origin. */
    suspend fun listNotes(notebookId: String, origin: NoteOrigin? = null): List<Note> {
        ensureReady()
        val result = if (origin == null) {
            db.execute(LIST_NOTES, listOf(notebookId))
        } else {
            db.execute(LIST_NOTES_BY_ORIGIN, listOf(notebookId, origin.name))
        }
        return result.rows.map(::noteOf)
    }

    /** Changes the title and text. The origin stays as it was. */
    suspend fun updateNote(id: String, title: String, body: String): Boolean {
        require(body.isNotBlank()) { "A note needs some text." }
        ensureReady()
        return db.execute(UPDATE_NOTE, listOf(title.trim(), body, now(), id)).affectedRows > 0
    }

    suspend fun deleteNote(id: String): Boolean {
        ensureReady()
        val results = db.transaction(listOf(Stmt(DELETE_NOTE_CITATIONS, listOf(id)), Stmt(DELETE_NOTE, listOf(id))))
        return results.last().affectedRows > 0
    }

    /** True if a new citation was added; false if the note already cited the source or either one is missing. */
    suspend fun citeSource(noteId: String, sourceId: String): Boolean {
        ensureReady()
        return db.execute(CITE_SOURCE, listOf(noteId, sourceId, noteId, sourceId)).affectedRows > 0
    }

    suspend fun removeCitation(noteId: String, sourceId: String): Boolean {
        ensureReady()
        return db.execute(REMOVE_CITATION, listOf(noteId, sourceId)).affectedRows > 0
    }

    suspend fun listCitedSources(noteId: String): List<SourceInfo> {
        ensureReady()
        return db.execute(LIST_CITED_SOURCES, listOf(noteId)).rows.map(::infoOf)
    }

    // ---- Helpers ----

    private suspend fun notebookExists(id: String) = db.execute(NOTEBOOK_EXISTS, listOf(id)).rows.isNotEmpty()

    private suspend fun requireSourcesExist(ids: List<String>) {
        if (ids.isEmpty()) return
        val marks = ids.joinToString(", ") { "?" }
        val found = db.execute("SELECT id FROM sources WHERE id IN ($marks)", ids).rows.size
        require(found == ids.size) { "A cited source does not exist." }
    }

    private fun notebookOf(r: List<String?>) = Notebook(
        id = r[0].orEmpty(),
        name = r[1].orEmpty(),
        description = r[2].orEmpty(),
        archived = r[3] == "1",
        createdAt = r[4]?.toLongOrNull() ?: 0L,
        updatedAt = r[5]?.toLongOrNull() ?: 0L,
    )

    private fun infoOf(r: List<String?>) = SourceInfo(
        id = r[0].orEmpty(),
        kind = SourceKind.valueOf(r[1].orEmpty()),
        title = r[2].orEmpty(),
        url = r[3],
        contentHash = r[4].orEmpty(),
        characters = r[5]?.toLongOrNull() ?: 0L,
        fetchedAt = r[6]?.toLongOrNull(),
        createdAt = r[7]?.toLongOrNull() ?: 0L,
        updatedAt = r[8]?.toLongOrNull() ?: 0L,
    )

    private fun noteOf(r: List<String?>) = Note(
        id = r[0].orEmpty(),
        notebookId = r[1].orEmpty(),
        origin = NoteOrigin.valueOf(r[2].orEmpty()),
        title = r[3].orEmpty(),
        body = r[4].orEmpty(),
        createdAt = r[5]?.toLongOrNull() ?: 0L,
        updatedAt = r[6]?.toLongOrNull() ?: 0L,
    )

    private companion object {
        const val TITLE_FROM_TEXT = 60

        const val NOTEBOOK_COLS = "id, name, description, archived, created_at, updated_at"
        const val INFO_COLS = "s.id, s.kind, s.title, s.url, s.content_hash, length(s.content), s.fetched_at, s.created_at, s.updated_at"
        const val NOTE_COLS = "id, notebook_id, origin, title, body, created_at, updated_at"

        const val INSERT_NOTEBOOK = "INSERT INTO notebooks (id, name, description, archived, created_at, updated_at) VALUES (?, ?, ?, 0, ?, ?)"
        const val SELECT_NOTEBOOK = "SELECT $NOTEBOOK_COLS FROM notebooks WHERE id = ?"
        const val NOTEBOOK_EXISTS = "SELECT 1 FROM notebooks WHERE id = ?"
        const val LIST_NOTEBOOKS_ACTIVE = "SELECT $NOTEBOOK_COLS FROM notebooks WHERE archived = 0 ORDER BY updated_at DESC, id"
        const val LIST_NOTEBOOKS_ALL = "SELECT $NOTEBOOK_COLS FROM notebooks ORDER BY updated_at DESC, id"
        const val UPDATE_NOTEBOOK = "UPDATE notebooks SET name = ?, description = ?, updated_at = ? WHERE id = ?"
        const val SET_ARCHIVED = "UPDATE notebooks SET archived = ?, updated_at = ? WHERE id = ?"
        const val DELETE_NOTEBOOK_NOTE_SOURCES = "DELETE FROM note_sources WHERE note_id IN (SELECT id FROM notes WHERE notebook_id = ?)"
        const val DELETE_NOTEBOOK_NOTES = "DELETE FROM notes WHERE notebook_id = ?"
        const val DELETE_NOTEBOOK_LINKS = "DELETE FROM notebook_sources WHERE notebook_id = ?"
        const val DELETE_NOTEBOOK = "DELETE FROM notebooks WHERE id = ?"

        const val INSERT_TEXT_SOURCE = "INSERT INTO sources (id, kind, title, content, url, content_hash, fetched_at, created_at, updated_at) VALUES (?, 'TEXT', ?, ?, NULL, ?, NULL, ?, ?)"
        const val UPSERT_WEB_SOURCE = "INSERT INTO sources (id, kind, title, content, url, content_hash, fetched_at, created_at, updated_at) VALUES (?, 'WEB', ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (url) WHERE url IS NOT NULL DO UPDATE SET title = excluded.title, content = excluded.content, content_hash = excluded.content_hash, fetched_at = excluded.fetched_at, updated_at = excluded.updated_at"
        const val SELECT_SOURCE_BY_URL = "SELECT id, created_at FROM sources WHERE url = ?"
        const val SELECT_SOURCE = "SELECT $INFO_COLS, s.content FROM sources s WHERE s.id = ?"
        const val LIST_SOURCES = "SELECT $INFO_COLS FROM sources s ORDER BY s.updated_at DESC, s.id"
        const val LIST_NOTEBOOK_SOURCES = "SELECT $INFO_COLS FROM sources s JOIN notebook_sources ns ON ns.source_id = s.id WHERE ns.notebook_id = ? ORDER BY ns.added_at DESC, s.id"
        const val DELETE_SOURCE_CITATIONS = "DELETE FROM note_sources WHERE source_id = ?"
        const val DELETE_SOURCE_LINKS = "DELETE FROM notebook_sources WHERE source_id = ?"
        const val DELETE_SOURCE = "DELETE FROM sources WHERE id = ?"
        const val ATTACH_SOURCE = "INSERT OR IGNORE INTO notebook_sources (notebook_id, source_id, added_at) SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM notebooks WHERE id = ?) AND EXISTS (SELECT 1 FROM sources WHERE id = ?)"
        const val DETACH_SOURCE = "DELETE FROM notebook_sources WHERE notebook_id = ? AND source_id = ?"

        const val INSERT_NOTE = "INSERT INTO notes (id, notebook_id, origin, title, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
        const val INSERT_NOTE_SOURCE = "INSERT INTO note_sources (note_id, source_id) VALUES (?, ?)"
        const val SELECT_NOTE = "SELECT $NOTE_COLS FROM notes WHERE id = ?"
        const val LIST_NOTES = "SELECT $NOTE_COLS FROM notes WHERE notebook_id = ? ORDER BY updated_at DESC, id"
        const val LIST_NOTES_BY_ORIGIN = "SELECT $NOTE_COLS FROM notes WHERE notebook_id = ? AND origin = ? ORDER BY updated_at DESC, id"
        const val UPDATE_NOTE = "UPDATE notes SET title = ?, body = ?, updated_at = ? WHERE id = ?"
        const val DELETE_NOTE_CITATIONS = "DELETE FROM note_sources WHERE note_id = ?"
        const val DELETE_NOTE = "DELETE FROM notes WHERE id = ?"
        const val CITE_SOURCE = "INSERT OR IGNORE INTO note_sources (note_id, source_id) SELECT ?, ? WHERE EXISTS (SELECT 1 FROM notes WHERE id = ?) AND EXISTS (SELECT 1 FROM sources WHERE id = ?)"
        const val REMOVE_CITATION = "DELETE FROM note_sources WHERE note_id = ? AND source_id = ?"
        const val LIST_CITED_SOURCES = "SELECT $INFO_COLS FROM sources s JOIN note_sources c ON c.source_id = s.id WHERE c.note_id = ? ORDER BY s.title, s.id"
    }
}
