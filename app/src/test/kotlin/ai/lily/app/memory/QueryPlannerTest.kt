package ai.lily.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryPlannerTest {
    @Test
    fun aPlainListIsRead() {
        assertEquals(listOf("pasta recipe", "boil time"), QueryPlanner.parse("""["pasta recipe", "boil time"]"""))
    }

    @Test
    fun wordsAroundTheListAreIgnored() {
        val reply = "Sure! Here you go:\n```json\n[\"a\", \"b\"]\n```\nHope that helps."

        assertEquals(listOf("a", "b"), QueryPlanner.parse(reply))
    }

    @Test
    fun blanksRepeatsAndNonStringsAreDroppedAndTheListIsCapped() {
        val reply = """["a", "b", "a", 5, "  ", "c", "d"]"""

        assertEquals(listOf("a", "b", "c"), QueryPlanner.parse(reply))
    }

    @Test
    fun aReplyWithoutAListGivesNothing() {
        assertTrue(QueryPlanner.parse("I cannot help with that.").isEmpty())
        assertTrue(QueryPlanner.parse("[not json").isEmpty())
        assertTrue(QueryPlanner.parse("] [").isEmpty())
        assertTrue(QueryPlanner.parse("").isEmpty())
    }

    @Test
    fun aVeryLongQueryIsShortened() {
        val long = "x".repeat(500)

        assertEquals(200, QueryPlanner.parse("[\"$long\"]").single().length)
    }
}
