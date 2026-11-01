package ai.lily.app.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TursoWireTest {

    @Test
    fun transactionIsBeginStatementsCommitAndAConditionalRollback() {
        val body = TursoWire.transactionBody(listOf(Stmt("A", listOf(1, "x")), Stmt("B")))
        val steps = body.getJSONArray("requests").getJSONObject(0).getJSONObject("batch").getJSONArray("steps")
        assertEquals(5, steps.length())
        assertEquals("BEGIN", steps.getJSONObject(0).getJSONObject("stmt").getString("sql"))
        assertEquals("A", steps.getJSONObject(1).getJSONObject("stmt").getString("sql"))
        assertEquals(0, steps.getJSONObject(1).getJSONObject("condition").getInt("step"))
        assertEquals("B", steps.getJSONObject(2).getJSONObject("stmt").getString("sql"))
        assertEquals(1, steps.getJSONObject(2).getJSONObject("condition").getInt("step"))
        assertEquals("COMMIT", steps.getJSONObject(3).getJSONObject("stmt").getString("sql"))
        assertEquals(2, steps.getJSONObject(3).getJSONObject("condition").getInt("step"))
        val rollback = steps.getJSONObject(4)
        assertEquals("ROLLBACK", rollback.getJSONObject("stmt").getString("sql"))
        assertEquals("not", rollback.getJSONObject("condition").getString("type"))
        assertEquals(3, rollback.getJSONObject("condition").getJSONObject("cond").getInt("step"))
        val args = steps.getJSONObject(1).getJSONObject("stmt").getJSONArray("args")
        assertEquals("integer", args.getJSONObject(0).getString("type"))
        assertEquals("text", args.getJSONObject(1).getString("type"))
    }

    @Test
    fun emptyTransactionIsRefused() {
        try {
            TursoWire.transactionBody(emptyList())
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least one"))
        }
    }

    private val empty = """{"cols":[],"rows":[],"affected_rows":0}"""

    @Test
    fun aCommittedTransactionReturnsOneResultPerStatement() {
        val body = """{"results":[{"type":"ok","response":{"type":"batch","result":{
            "step_results":[$empty,
              {"cols":[{"name":"x"}],"rows":[[{"type":"integer","value":"1"}]],"affected_rows":0},
              {"cols":[],"rows":[],"affected_rows":1},$empty,null],
            "step_errors":[null,null,null,null,null]}}},
            {"type":"ok","response":{"type":"close"}}]}"""
        val results = TursoWire.parseTransaction(body, 2)
        assertEquals(2, results.size)
        assertEquals("1", results[0].rows[0][0])
        assertEquals(1L, results[1].affectedRows)
    }

    @Test
    fun aFailedStatementSurfacesTheDatabaseMessage() {
        val body = """{"results":[{"type":"ok","response":{"type":"batch","result":{
            "step_results":[$empty,null,null,null,$empty],
            "step_errors":[null,{"message":"no such table: x"},null,null,null]}}}]}"""
        try {
            TursoWire.parseTransaction(body, 2)
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("rolled back"))
            assertTrue(e.message!!.contains("no such table: x"))
        }
    }

    @Test
    fun aRequestLevelErrorIsReported() {
        val body = """{"results":[{"type":"error","error":{"message":"bad token"}}]}"""
        try {
            TursoWire.parseTransaction(body, 1)
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("bad token"))
        }
    }

    @Test
    fun executeReadsRowsAndNulls() {
        val body = """{"results":[{"type":"ok","response":{"type":"execute","result":{
            "cols":[{"name":"a"},{"name":"b"}],
            "rows":[[{"type":"text","value":"hi"},{"type":"null"}]],
            "affected_rows":0,"last_insert_rowid":"7"}}}]}"""
        val r = TursoWire.parseExecute(body)
        assertEquals(listOf("a", "b"), r.columns)
        assertEquals("hi", r.rows[0][0])
        assertNull(r.rows[0][1])
        assertEquals(7L, r.lastInsertRowid)
    }

    @Test
    fun valuesAreEncodedByType() {
        assertEquals("null", TursoWire.encodeValue(null).getString("type"))
        assertEquals("integer", TursoWire.encodeValue(5L).getString("type"))
        assertEquals("float", TursoWire.encodeValue(1.5).getString("type"))
        val text: JSONObject = TursoWire.encodeValue("s")
        assertEquals("text", text.getString("type"))
    }
}
