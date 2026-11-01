package ai.lily.app.memory

import ai.lily.app.settings.AppSettings

/**
 * The knowledge database (`knowledgeDb`): notebooks, sources and notes. Chunks and search arrive in later phases.
 * It is a third database. It is never the proven facts database or the journal.
 *
 * Ids are text (UUIDs made by the app) so a note and its citations can be written in one transaction.
 * Nothing here relies on foreign keys being enforced, because every request over Turso's HTTP API may be a new connection;
 * [KnowledgeRepository] removes dependent rows itself, inside one transaction.
 */
object KnowledgeSchema {
    const val COMPONENT = "knowledge"

    val migrations: List<Migration> = listOf(
        Migration(
            version = 1,
            description = "Base: a small key/value table for knowledge settings",
            statements = listOf(
                "CREATE TABLE IF NOT EXISTS knowledge_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            ),
        ),
        Migration(
            version = 2,
            description = "Notebooks, a shared library of sources (pasted text and web pages), and which notebooks use which source",
            statements = listOf(
                "CREATE TABLE IF NOT EXISTS notebooks (id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', archived INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK (kind IN ('TEXT', 'WEB')), title TEXT NOT NULL, content TEXT NOT NULL, url TEXT, content_hash TEXT NOT NULL, fetched_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, CHECK ((kind = 'WEB') = (url IS NOT NULL)))",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_sources_url ON sources (url) WHERE url IS NOT NULL",
                "CREATE TABLE IF NOT EXISTS notebook_sources (notebook_id TEXT NOT NULL, source_id TEXT NOT NULL, added_at INTEGER NOT NULL, PRIMARY KEY (notebook_id, source_id))",
                "CREATE INDEX IF NOT EXISTS idx_notebook_sources_source ON notebook_sources (source_id)",
            ),
        ),
        Migration(
            version = 3,
            description = "Notes (human, AI or imported) and the sources each note cites",
            statements = listOf(
                "CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY, notebook_id TEXT NOT NULL, origin TEXT NOT NULL CHECK (origin IN ('HUMAN', 'AI', 'IMPORTED')), title TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_notes_notebook ON notes (notebook_id, updated_at)",
                "CREATE TABLE IF NOT EXISTS note_sources (note_id TEXT NOT NULL, source_id TEXT NOT NULL, PRIMARY KEY (note_id, source_id))",
                "CREATE INDEX IF NOT EXISTS idx_note_sources_source ON note_sources (source_id)",
            ),
        ),
    )

    fun migrator(db: SqlDatabase) = SchemaMigrator(db, COMPONENT, migrations)

    private fun normalise(url: String) =
        url.trim().replaceFirst("libsql://", "https://").trimEnd('/').lowercase()

    /** A sentence if the knowledge database is set up in a way that could touch the other two, otherwise null. */
    fun configProblem(settings: AppSettings): String? {
        val k = settings.knowledgeDb ?: return null
        val url = normalise(k.url)
        if (url.isEmpty()) return "The knowledge database URL is empty."
        if (settings.factsDb?.let { normalise(it.url) } == url) {
            return "The knowledge database must not be the proven facts database."
        }
        if (settings.journalDb?.let { normalise(it.url) } == url) {
            return "The knowledge database must not be the journal database."
        }
        return null
    }
}
