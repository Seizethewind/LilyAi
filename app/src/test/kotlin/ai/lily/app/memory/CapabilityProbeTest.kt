package ai.lily.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A database whose answers come from the test. Statements whose text contains one of [breaks] throw. */
private class ScriptedDb(private val breaks: Set<String> = emptySet()) : SqlDatabase {
    private fun check(sql: String) {
        breaks.firstOrNull { sql.contains(it) }?.let { error("no such function: $it") }
    }

    override suspend fun execute(sql: String, args: List<Any?>): TursoResult {
        check(sql)
        return TursoResult(listOf("v"), listOf(listOf("0.0166")), 0, null)
    }

    override suspend fun transaction(statements: List<Stmt>): List<TursoResult> {
        statements.forEach { check(it.sql) }
        return statements.map { s ->
            if (s.sql.startsWith("SELECT count")) TursoResult(listOf("c"), listOf(listOf("1")), 0, null)
            else TursoResult(emptyList(), emptyList(), 0, null)
        }
    }
}

class CapabilityProbeTest {
    @Test
    fun everythingSupportedMeansEverythingInTheDatabase() = runBlocking {
        val r = CapabilityProbe(ScriptedDb()).run()
        assertTrue(r.transactions.supported && r.vector.supported && r.fullText.supported)
        assertEquals(SearchPath.IN_DATABASE, r.vectorSearch)
        assertEquals(SearchPath.IN_DATABASE, r.keywordSearch)
    }

    @Test
    fun noFullTextMeansKeywordSearchInTheApp() = runBlocking {
        val r = CapabilityProbe(ScriptedDb(setOf("fts5"))).run()
        assertFalse(r.fullText.supported)
        assertTrue(r.vector.supported)
        assertEquals(SearchPath.IN_APP, r.keywordSearch)
        assertEquals(SearchPath.IN_DATABASE, r.vectorSearch)
    }

    @Test
    fun noVectorMeansMeaningSearchInTheApp() = runBlocking {
        val r = CapabilityProbe(ScriptedDb(setOf("vector_distance_cos"))).run()
        assertFalse(r.vector.supported)
        assertEquals(SearchPath.IN_APP, r.vectorSearch)
        assertTrue(r.fullText.supported)
    }

    @Test
    fun theReasonIsKeptAndTheSummaryReadsPlainly() = runBlocking {
        val r = CapabilityProbe(ScriptedDb(setOf("fts5"))).run()
        assertTrue(r.fullText.detail.contains("fts5"))
        val text = r.summary()
        assertTrue(text.contains("full-text search: no (done in the app instead)"))
        assertTrue(text.contains("vector search: yes (done in the database)"))
    }
}
