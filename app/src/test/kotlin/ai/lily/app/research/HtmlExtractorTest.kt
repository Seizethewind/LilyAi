package ai.lily.app.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlExtractorTest {

    @Test
    fun titleAndBodyAreSeparated() {
        val page = HtmlExtractor.extract(
            "<html><head><title> My  Page </title><style>p{}</style></head><body><h1>Hello</h1><p>One &amp; two</p></body></html>",
        )

        assertEquals("My Page", page.title)
        assertEquals("Hello\n\nOne & two", page.text)
        assertFalse(page.truncated)
    }

    @Test
    fun aTitleWithTagsAndEntitiesIsCleaned() {
        assertEquals("A & B", HtmlExtractor.extract("<title>A &amp; <b>B</b></title>").title)
        assertEquals("", HtmlExtractor.extract("<p>no title</p>").title)
    }

    @Test
    fun scriptsStylesAndCommentsAreDroppedWithTheirContents() {
        val page = HtmlExtractor.extract(
            "<p>Keep</p><script>var x = '<p>no</p>';</script><style>.a{}</style><noscript>Enable JS</noscript><p>Also</p>a<!-- hidden <b>x</b> -->b",
        )

        assertEquals("Keep\n\nAlso\nab", page.text)
    }

    @Test
    fun aPageThatOnlyBuildsItsTextInJavaScriptComesOutEmpty() {
        assertEquals("", HtmlExtractor.extract("<html><body><script>render()</script></body></html>").text)
    }

    @Test
    fun blockTagsBecomeLinesSoLineNumbersStayMeaningful() {
        assertEquals("a\n\nb", HtmlExtractor.extract("<p>a</p><p>b</p>").text)
        assertEquals("a\nb", HtmlExtractor.extract("a<br>b").text)
        assertEquals("one\ntwo", HtmlExtractor.extract("<ul><li>one</li><li>two</li></ul>").text.replace("\n\n", "\n"))
        assertEquals("x y", HtmlExtractor.extract("<table><tr><td>x</td><td>y</td></tr></table>").text)
    }

    @Test
    fun aHeaderElementIsNotMistakenForTheHead() {
        assertTrue(HtmlExtractor.extract("<header>Top</header><p>x</p>").text.contains("Top"))
    }

    @Test
    fun entitiesAreDecodedAfterTagsAreRemoved() {
        val page = HtmlExtractor.extract("&lt;b&gt; &#65;&#x42; &unknown; &nbsp;x &#0; &#xD800;")

        assertEquals("<b> AB &unknown; x &#0; &#xD800;", page.text)
    }

    @Test
    fun aLessThanSignInTextIsNotTreatedAsATag() {
        assertEquals("if a < b and c > d", HtmlExtractor.extract("<p>if a < b and c > d</p>").text)
    }

    @Test
    fun whitespaceIsTidied() {
        val page = HtmlExtractor.extract("<p>  a \t\t b  </p>\r\n\r\n\r\n\r\n<p>c</p>")

        assertEquals("a b\n\nc", page.text)
    }

    @Test
    fun longTextIsCutAtTheLimitAndSaysSo() {
        val page = HtmlExtractor.extract("<p>" + "a".repeat(100) + "</p>", maxChars = 10)

        assertEquals("a".repeat(10), page.text)
        assertTrue(page.truncated)
    }

    @Test
    fun theCutNeverSplitsASurrogatePair() {
        val page = HtmlExtractor.fromPlainText("\uD83D\uDE00".repeat(10), maxChars = 5)

        assertEquals("\uD83D\uDE00".repeat(2), page.text)
        assertTrue(page.truncated)
    }

    @Test
    fun plainTextIsOnlyTidied() {
        val page = HtmlExtractor.fromPlainText("  a  b \r\n\r\n\r\n\r\nc  ", maxChars = 100)

        assertEquals("a b\n\nc", page.text)
        assertEquals("", page.title)
    }
}
