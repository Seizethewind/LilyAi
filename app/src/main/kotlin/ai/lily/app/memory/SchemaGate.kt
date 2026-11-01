package ai.lily.app.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Brings a database up to date the first time it is needed, then does nothing. Shared by everything that reads or writes the
 * knowledge database, so no caller has to remember a separate set-up step. Migrations are safe to run twice, so two gates
 * on the same database cannot hurt each other.
 */
class SchemaGate(private val migrator: SchemaMigrator) {
    private val lock = Mutex()
    private var ready = false

    suspend fun ensure() {
        if (ready) return
        lock.withLock {
            if (!ready) {
                migrator.migrate()
                ready = true
            }
        }
    }
}
