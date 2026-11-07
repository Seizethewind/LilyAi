package ai.lily.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditReportTest {

    private val sample = """{"width":360,"height":740,"lang":false,"viewportMeta":true,"h1":0,"overflowX":true,
        "checks":[{"id":"image-alt","checked":10,"bad":3,"examples":["a.png","b.png"]},
                  {"id":"contrast","checked":50,"bad":0,"examples":[]}]}"""

    @Test
    fun theReportReadsAsPlainText() {
        val text = AuditReport.parse(sample)!!.toText()
        assertTrue(text, text.contains("Layout width 360 px, height 740 px."))
        assertTrue(text, text.contains("viewport tag yes; page language set no; h1 headings 0; sideways scrolling yes."))
        assertTrue(text, text.contains("- Images without alt text: 3 of 10 (e.g. a.png; b.png)"))
        assertTrue(text, text.contains("- Text with low contrast (approximate): none of 50 checked"))
    }

    @Test
    fun anUnknownCheckIsShownByItsId() {
        val json = """{"width":1,"height":1,"lang":true,"viewportMeta":true,"h1":1,"overflowX":false,
            "checks":[{"id":"made-up","checked":2,"bad":1,"examples":[]}]}"""
        assertTrue(AuditReport.parse(json)!!.toText().contains("- made-up: 1 of 2"))
    }

    @Test
    fun examplesAreCappedCutAndFlattened() {
        val many = """{"width":1,"height":1,"lang":true,"viewportMeta":true,"h1":1,"overflowX":false,
            "checks":[{"id":"image-alt","checked":9,"bad":7,"examples":["a","b","c","d","e","f","g"]}]}"""
        assertEquals(5, AuditReport.parse(many)!!.checks.single().examples.size)

        val odd = """{"width":1,"height":1,"lang":true,"viewportMeta":true,"h1":1,"overflowX":false,
            "checks":[{"id":"image-alt","checked":2,"bad":2,"examples":["line one\nline two","${"y".repeat(200)}"]}]}"""
        val examples = AuditReport.parse(odd)!!.checks.single().examples
        assertEquals("line one line two", examples[0])
        assertEquals(80, examples[1].length)
    }

    @Test
    fun anythingElseGivesNull() {
        assertNull(AuditReport.parse(null))
        assertNull(AuditReport.parse(""))
        assertNull(AuditReport.parse("not json"))
        // The script's own error answer has no checks.
        assertNull(AuditReport.parse("""{"error":"TypeError: x"}"""))
    }
}
