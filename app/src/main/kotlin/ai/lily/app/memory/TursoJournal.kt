package ai.lily.app.memory

import ai.lily.app.core.Journal
import ai.lily.app.core.JournalEntry

/** Journal store: raw history of requests, decisions and results. */
class TursoJournal(private val db: TursoClient) : Journal {
    private var ready = false

    private suspend fun init() {
        if (ready) return
        db.execute(
            "CREATE TABLE IF NOT EXISTS journal (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER NOT NULL, project TEXT, kind TEXT NOT NULL, content TEXT NOT NULL)"
        )
        ready = true
    }

    override suspend fun append(kind: String, content: String, project: String?): Long {
        init()
        val r = db.execute(
            "INSERT INTO journal (ts, project, kind, content) VALUES (?, ?, ?, ?)",
            listOf(System.currentTimeMillis(), project, kind, content),
        )
        return r.lastInsertRowid ?: -1L
    }

    override suspend fun recent(limit: Int): List<JournalEntry> {
        init()
        val r = db.execute(
            "SELECT id, ts, project, kind, content FROM journal ORDER BY id DESC LIMIT ?",
            listOf(limit),
        )
        return r.rows.map {
            JournalEntry(
                id = it[0]?.toLongOrNull() ?: 0L,
                timestamp = it[1]?.toLongOrNull() ?: 0L,
                project = it[2],
                kind = it[3].orEmpty(),
                content = it[4].orEmpty(),
            )
        }
    }
}
