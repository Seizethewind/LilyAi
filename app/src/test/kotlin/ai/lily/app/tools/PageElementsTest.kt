package ai.lily.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageElementsTest {

    private val sample = """[
        {"i":0,"tag":"input","type":"email","label":"Email","hint":"you@example.com","value":"","href":""},
        {"i":1,"tag":"button","type":"","label":"Sign\nin","hint":"","value":"","href":""},
        {"i":2,"tag":"a","type":"","label":"Help","hint":"","value":"","href":"/help"}]"""

    @Test
    fun theListIsReadAndFlattened() {
        val list = PageElements.parse(sample)!!
        assertEquals(3, list.size)
        assertEquals(PageElement(0, "input[email]", "Email", "hint \"you@example.com\""), list[0])
        assertEquals("Sign in", list[1].label)
        assertEquals("to /help", list[2].detail)
    }

    @Test
    fun theListReadsAsLinesForAModel() {
        val text = PageElements.format(PageElements.parse(sample)!!)
        assertTrue(text, text.contains("[0] input[email] \"Email\" (hint \"you@example.com\")"))
        assertTrue(text, text.contains("[1] button \"Sign in\""))
    }

    @Test
    fun anEmptyPageSaysSo() {
        assertTrue(PageElements.format(emptyList()).contains("nothing on the page"))
    }

    @Test
    fun brokenAnswersGiveNull() {
        assertNull(PageElements.parse(null))
        assertNull(PageElements.parse(""))
        assertNull(PageElements.parse("not json"))
        assertNull(PageElements.parse("""{"a":1}"""))
    }

    @Test
    fun atMostSixtyItemsAreKept() {
        val many = (0 until 80).joinToString(",", "[", "]") { """{"i":$it,"tag":"a","label":"x"}""" }
        assertEquals(60, PageElements.parse(many)!!.size)
    }
}
