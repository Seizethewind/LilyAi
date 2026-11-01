package ai.lily.app.memory

import ai.lily.app.settings.AppSettings

/**
 * The knowledge database (`knowledgeDb`): notebooks, sources, notes and chunks arrive in later phases.
 * It is a third database. It is never the proven facts database or the journal.
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
