package ai.lily.app.lib.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectReplaceTest {

    @Test
    fun everyMatchIsReplacedAndCounted() {
        val r = planReplace("a1 b1", Regex("1"), "2", regexMode = false)
        assertNotNull(r)
        assertEquals("a2 b2", r!!.newText)
        assertEquals(2, r.count)
    }

    @Test
    fun nothingToReplaceGivesNull() {
        assertNull(planReplace("abc", Regex("zzz"), "y", regexMode = false))
    }

    @Test
    fun plainModeKeepsDollarSignsLiteral() {
        assertEquals("\$1", planReplace("x", Regex("x"), "\$1", regexMode = false)!!.newText)
    }

    @Test
    fun regexModeUsesGroups() {
        val r = planReplace("a1 b2", Regex("([a-z])(\\d)"), "\$2\$1", regexMode = true)
        assertEquals("1a 2b", r!!.newText)
        assertEquals(2, r.count)
    }

    @Test
    fun emptyMatchesAreLeftAlone() {
        assertNull(planReplace("abc", Regex("x*"), "-", regexMode = true))
        val r = planReplace("axb", Regex("x*"), "-", regexMode = true)
        assertEquals("a-b", r!!.newText)
        assertEquals(1, r.count)
    }

    @Test
    fun deletingMatchesWorks() {
        assertEquals("ac", planReplace("abc", Regex("b"), "", regexMode = false)!!.newText)
    }

    @Test
    fun binaryTextIsSpotted() {
        assertTrue(isProbablyBinary("ab\u0000cd"))
        assertFalse(isProbablyBinary("plain text"))
    }
}
