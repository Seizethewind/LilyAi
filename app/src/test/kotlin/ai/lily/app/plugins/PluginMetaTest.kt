package ai.lily.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginMetaTest {
    private fun with(fields: String) = TestManifests.json().replaceFirst("\"schema\": 1,", "\"schema\": 1, $fields,")

    private fun parsed(text: String) = (ManifestParser.parse(text) as ParseResult.Ok).manifest

    private fun errors(text: String) = (ManifestParser.parse(text) as ParseResult.Invalid).errors

    @Test
    fun authorIconAndAccentAreRead() {
        val m = parsed(with("\"author\": \"Me\", \"icon\": \"\uD83D\uDC19\", \"accent\": \"#1A2B3C\""))
        assertEquals(listOf("Me", "\uD83D\uDC19", "#1A2B3C"), listOf(m.author, m.icon, m.accent))
    }

    @Test
    fun twoLettersAreAGoodIcon() {
        assertEquals("GH", parsed(with("\"icon\": \"GH\"")).icon)
    }

    @Test
    fun theyAreOptional() {
        val m = parsed(TestManifests.json())
        assertEquals(listOf("", "", ""), listOf(m.author, m.icon, m.accent))
    }

    @Test
    fun aBadAccentIsRefused() {
        assertTrue(errors(with("\"accent\": \"red\"")).any { "accent" in it })
        assertTrue(errors(with("\"accent\": \"#12345\"")).any { "accent" in it })
    }

    @Test
    fun aBadIconIsRefused() {
        for (bad in listOf("\"abc\"", "\"\"", "\" \"", "\"a b\"", "5", "\"a\u200Db\"")) {
            assertTrue("icon $bad", errors(with("\"icon\": $bad")).any { "icon" in it })
        }
    }

    @Test
    fun aLongAuthorIsRefused() {
        assertTrue(errors(with("\"author\": \"${"x".repeat(61)}\"")).any { "author" in it })
    }
}
