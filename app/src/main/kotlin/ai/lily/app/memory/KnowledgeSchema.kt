package ai.lily.app.memory

import ai.lily.app.settings.AppSettings

/**
 * The knowledge database (`knowledgeDb`): notebooks, sources, notes, the chunks of each source, the meaning vectors of those chunks,
 * and (v6) context levels, saved prompts, insights and chats.
 * It is a third database. It is never the proven facts database or the journal.
 *
 * Ids are text (UUIDs made by the app) so a note and its citations can be written in one transaction.
 * Nothing here relies on foreign keys being enforced, because every request over Turso's HTTP API may be a new connection;
 * [KnowledgeRepository] removes dependent rows itself, inside one transaction.
 */
object KnowledgeSchema {
    const val COMPONENT = "knowledge"

    /**
     * A SQL condition for a join that has `notebook_sources` as `ns`: true when the source is at the FULL context level in that
     * notebook. FULL is stored as no row in `source_context`, so everything that existed before v6 counts as FULL.
     */
    const val ONLY_FULL_CONTEXT =
        "NOT EXISTS (SELECT 1 FROM source_context sc WHERE sc.notebook_id = ns.notebook_id AND sc.source_id = ns.source_id)"

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
        Migration(
            version = 4,
            description = "Chunks of each source with their line ranges, and a record of what each source was last chunked from",
            statements = listOf(
                "CREATE TABLE IF NOT EXISTS chunks (id TEXT PRIMARY KEY, source_id TEXT NOT NULL, ordinal INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, text TEXT NOT NULL, tokens INTEGER NOT NULL, created_at INTEGER NOT NULL)",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_chunks_source_ordinal ON chunks (source_id, ordinal)",
                "CREATE TABLE IF NOT EXISTS source_index (source_id TEXT PRIMARY KEY, content_hash TEXT NOT NULL, settings TEXT NOT NULL, chunk_count INTEGER NOT NULL, indexed_at INTEGER NOT NULL)",
            ),
        ),
        Migration(
            version = 5,
            description = "One meaning vector per chunk: which model made it, how many numbers it has, and the numbers as base64 text",
            statements = listOf(
                // Turso's HTTP wire here has no blob type, so the float32 numbers are kept as base64 text (see VectorCodec).
                "CREATE TABLE IF NOT EXISTS chunk_embeddings (chunk_id TEXT PRIMARY KEY, source_id TEXT NOT NULL, model TEXT NOT NULL, dims INTEGER NOT NULL, vector TEXT NOT NULL, created_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_model ON chunk_embeddings (model, chunk_id)",
                "CREATE INDEX IF NOT EXISTS idx_chunk_embeddings_source ON chunk_embeddings (source_id)",
            ),
        ),
        Migration(
            version = 6,
            description = "Context levels per notebook source, saved prompts, insights, and chat sessions with their messages",
            statements = listOf(
                // A row exists only for a source set to OFF or INSIGHTS; no row means FULL. Only CREATE ... IF NOT EXISTS is used (no ALTER),
                // so two callers migrating at the same moment cannot break each other.
                "CREATE TABLE IF NOT EXISTS source_context (notebook_id TEXT NOT NULL, source_id TEXT NOT NULL, level TEXT NOT NULL CHECK (level IN ('OFF', 'INSIGHTS')), updated_at INTEGER NOT NULL, PRIMARY KEY (notebook_id, source_id))",
                "CREATE INDEX IF NOT EXISTS idx_source_context_source ON source_context (source_id)",
                // Built-in prompts live in code, not here; this table holds only the person's own.
                "CREATE TABLE IF NOT EXISTS saved_prompts (id TEXT PRIMARY KEY, title TEXT NOT NULL, prompt TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                // One current insight per source and prompt. The hashes say what it was made from, so a changed source or prompt shows as stale.
                "CREATE TABLE IF NOT EXISTS source_insights (id TEXT PRIMARY KEY, source_id TEXT NOT NULL, prompt_id TEXT NOT NULL, prompt_title TEXT NOT NULL, prompt_hash TEXT NOT NULL, source_hash TEXT NOT NULL, body TEXT NOT NULL, truncated INTEGER NOT NULL DEFAULT 0, routed_via TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL)",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_source_insights_pair ON source_insights (source_id, prompt_id)",
                "CREATE TABLE IF NOT EXISTS chat_sessions (id TEXT PRIMARY KEY, notebook_id TEXT NOT NULL, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_chat_sessions_notebook ON chat_sessions (notebook_id, updated_at)",
                // citations and notes are JSON text.
                "CREATE TABLE IF NOT EXISTS chat_messages (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, ordinal INTEGER NOT NULL, role TEXT NOT NULL CHECK (role IN ('USER', 'ASSISTANT')), body TEXT NOT NULL, citations TEXT NOT NULL DEFAULT '[]', notes TEXT NOT NULL DEFAULT '[]', created_at INTEGER NOT NULL)",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_messages_order ON chat_messages (session_id, ordinal)",
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
