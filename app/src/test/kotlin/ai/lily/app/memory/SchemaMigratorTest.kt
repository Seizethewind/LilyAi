package ai.lily.app.memory

import ai.lily.app.settings.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** An in-memory stand-in for a database. A statement containing FAIL throws, and then the whole transaction is dropped. */
private class FakeDb(var version: Int? = null) : SqlDatabase {
    val ran = mutableListOf<String>()

    private fun empty() = TursoResult(emptyList(), emptyList(), 0, null)

    override suspend fun execute(sql: String, args: List<Any?>): TursoResult {
        if (sql.startsWith("SELECT version")) {
            val rows = version?.let { listOf(listOf<String?>(it.toString())) } ?: emptyList()
            return TursoResult(listOf("version"), rows, 0, null)
        }
        return empty()
    }

    override suspend fun transaction(statements: List<Stmt>): List<TursoResult> {
        var pending = version
        val done = mutableListOf<String>()
        for (s in statements) {
            if (s.sql.contains("FAIL")) error("boom")
            if (s.sql.startsWith("INSERT INTO schema_version")) pending = (s.args[1] as Int) else done += s.sql
        }
        version = pending
        ran += done
        return statements.map { empty() }
    }
}

class SchemaMigratorTest {
    private val m1 = Migration(1, "one", listOf("CREATE TABLE a (x)"))
    private val m2 = Migration(2, "two", listOf("CREATE TABLE b (x)", "CREATE INDEX i ON b (x)"))

    @Test
    fun freshDatabaseGetsEveryMigration() = runBlocking {
        val db = FakeDb()
        val report = SchemaMigrator(db, "k", listOf(m1, m2)).migrate()
        assertEquals(0, report.from)
        assertEquals(2, report.to)
        assertEquals(listOf(1, 2), report.applied)
        assertEquals(2, db.version)
        assertEquals(3, db.ran.size)
    }

    @Test
    fun runningAgainDoesNothing() = runBlocking {
        val db = FakeDb()
        val migrator = SchemaMigrator(db, "k", listOf(m1, m2))
        migrator.migrate()
        val again = migrator.migrate()
        assertFalse(again.changed)
        assertEquals(2, again.to)
        assertEquals(3, db.ran.size)
    }

    @Test
    fun onlyMissingMigrationsRun() = runBlocking {
        val db = FakeDb(version = 1)
        val report = SchemaMigrator(db, "k", listOf(m1, m2)).migrate()
        assertEquals(listOf(2), report.applied)
        assertEquals(listOf("CREATE TABLE b (x)", "CREATE INDEX i ON b (x)"), db.ran)
    }

    @Test
    fun aFailedMigrationLeavesTheOldVersion() = runBlocking {
        val db = FakeDb()
        val bad = Migration(2, "bad", listOf("CREATE TABLE b (x)", "FAIL"))
        try {
            SchemaMigrator(db, "k", listOf(m1, bad)).migrate()
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, db.version)
        assertEquals(listOf("CREATE TABLE a (x)"), db.ran)
    }

    @Test
    fun aNewerDatabaseIsRefused() = runBlocking {
        val db = FakeDb(version = 5)
        try {
            SchemaMigrator(db, "k", listOf(m1, m2)).migrate()
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Update the app"))
        }
        assertEquals(5, db.version)
        assertTrue(db.ran.isEmpty())
    }

    @Test
    fun migrationsMustCountUpFromOne() {
        try {
            SchemaMigrator(FakeDb(), "k", listOf(m2))
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("out of order"))
        }
    }

    @Test
    fun knowledgeSchemaIsValid() = runBlocking {
        val db = FakeDb()
        val report = KnowledgeSchema.migrator(db).migrate()
        assertEquals(KnowledgeSchema.migrations.size, report.to)
    }
}

class KnowledgeConfigTest {
    private val facts = TursoConfig("libsql://facts-me.turso.io", "t")
    private val journal = TursoConfig("libsql://journal-me.turso.io/", "t")

    @Test
    fun notConfiguredIsFine() {
        assertNull(KnowledgeSchema.configProblem(AppSettings(factsDb = facts)))
    }

    @Test
    fun aSeparateDatabaseIsFine() {
        val s = AppSettings(factsDb = facts, journalDb = journal, knowledgeDb = TursoConfig("libsql://knowledge-me.turso.io", "t"))
        assertNull(KnowledgeSchema.configProblem(s))
    }

    @Test
    fun sameAsFactsIsRefusedEvenWithDifferentSpelling() {
        val s = AppSettings(factsDb = facts, knowledgeDb = TursoConfig(" https://Facts-Me.turso.io/ ", "t"))
        assertNotNull(KnowledgeSchema.configProblem(s))
    }

    @Test
    fun sameAsJournalIsRefused() {
        val s = AppSettings(journalDb = journal, knowledgeDb = TursoConfig("libsql://journal-me.turso.io", "t"))
        assertNotNull(KnowledgeSchema.configProblem(s))
    }
}
