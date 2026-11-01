package ai.lily.app.memory

/** One step in a database's history. [version] counts up from 1 with no gaps. Never edit a migration once it has shipped. */
class Migration(val version: Int, val description: String, val statements: List<String>)

data class MigrationReport(val from: Int, val to: Int, val applied: List<Int>) {
    val changed: Boolean get() = applied.isNotEmpty()
}

/**
 * Keeps a database's tables at the version this app expects. The version of each [component] is stored in `schema_version`.
 * Every migration runs as one transaction together with the version bump, so a failure leaves the database at the old version.
 * A database that is newer than this app knows is refused rather than guessed at.
 */
class SchemaMigrator(
    private val db: SqlDatabase,
    private val component: String,
    private val migrations: List<Migration>,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(component.isNotBlank()) { "component is empty" }
        migrations.forEachIndexed { i, m ->
            require(m.version == i + 1) { "migration ${m.version} is out of order (expected ${i + 1})" }
            require(m.statements.isNotEmpty()) { "migration ${m.version} has no statements" }
        }
    }

    val latest: Int get() = migrations.size

    suspend fun currentVersion(): Int {
        db.execute(CREATE_VERSION_TABLE)
        val r = db.execute("SELECT version FROM schema_version WHERE component = ?", listOf(component))
        return r.rows.firstOrNull()?.firstOrNull()?.toIntOrNull() ?: 0
    }

    suspend fun migrate(): MigrationReport {
        val from = currentVersion()
        check(from <= latest) {
            "The $component database is at version $from but this app only knows up to $latest. Update the app."
        }
        val applied = mutableListOf<Int>()
        for (m in migrations.filter { it.version > from }) {
            val steps = m.statements.map { Stmt(it) } + Stmt(BUMP, listOf(component, m.version, now()))
            db.transaction(steps)
            applied += m.version
        }
        return MigrationReport(from = from, to = from + applied.size, applied = applied)
    }

    private companion object {
        const val CREATE_VERSION_TABLE =
            "CREATE TABLE IF NOT EXISTS schema_version (" +
                "component TEXT PRIMARY KEY, version INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
        const val BUMP =
            "INSERT INTO schema_version (component, version, updated_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(component) DO UPDATE SET version = excluded.version, updated_at = excluded.updated_at"
    }
}
