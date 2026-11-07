package ai.lily.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsResultTest {

    @Test
    fun aStringResultIsUnquotedAndUnescaped() {
        // What evaluateJavascript returns for a script that returns the text {"a":1}.
        val raw = "\"" + """{\"a\":1}""" + "\""
        assertEquals("""{"a":1}""", JsResult.text(raw))
    }

    @Test
    fun unicodeEscapesAreDecoded() {
        assertEquals("a<b", JsResult.text("\"a\\u003Cb\""))
    }

    @Test
    fun nullAndMissingAnswersGiveNull() {
        assertNull(JsResult.text("null"))
        assertNull(JsResult.text(null))
        assertNull(JsResult.text(""))
    }

    @Test
    fun anAnswerThatIsNotAStringGivesNull() {
        assertNull(JsResult.text("42"))
        assertNull(JsResult.text("""{"a":1}"""))
    }

    @Test
    fun aSnapshotIsReadAndItsTextIsCut() {
        val raw = "\"" + """{\"url\":\"https://x.test/\",\"title\":\"Hi\",\"text\":\"abcdef\"}""" + "\""
        val s = JsResult.snapshot(raw, maxText = 3)!!
        assertEquals("https://x.test/", s.url)
        assertEquals("Hi", s.title)
        assertEquals("abc", s.text)
    }

    @Test
    fun aBrokenSnapshotGivesNull() {
        assertNull(JsResult.snapshot("\"not json\"", 10))
        assertNull(JsResult.snapshot(null, 10))
    }
}
