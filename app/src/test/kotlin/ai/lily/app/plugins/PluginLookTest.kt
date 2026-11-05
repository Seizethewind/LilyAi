package ai.lily.app.plugins

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLookTest {
    private fun parse(vararg extra: Pair<String, Any>): ParseResult {
        val o = JSONObject().put("schema", 1).put("id", "demo.plugin").put("name", "Demo Plugin").put("version", "1.0.0").put("description", "d")
        for ((k, v) in extra) o.put(k, v)
        return ManifestParser.parse(o.toString())
    }

    private fun errors(r: ParseResult): List<String> = (r as ParseResult.Invalid).errors

    @Test
    fun authorIconAndAccentAreReadFromTheManifest() {
        val m = (parse("author" to "Lily", "icon" to ">_", "accent" to "#2B2B2B") as ParseResult.Ok).manifest
        assertEquals("Lily", m.author)
        assertEquals(">_", m.icon)
        assertEquals("#2B2B2B", m.accent)
    }

    @Test
    fun theyAreEmptyWhenTheManifestHasNone() {
        val m = (parse() as ParseResult.Ok).manifest
        assertEquals("", m.author)
        assertEquals("", m.icon)
        assertEquals("", m.accent)
    }

    @Test
    fun aBadAccentATooLongIconAndAHiddenCharacterAreRefused() {
        assertTrue(errors(parse("accent" to "red")).any { it.contains("accent") })
        assertTrue(errors(parse("accent" to "#12345")).any { it.contains("accent") })
        assertTrue(errors(parse("icon" to "ABCDE")).any { it.contains("icon") })
        assertTrue(errors(parse("icon" to "\u202E")).any { it.contains("icon") })
        assertTrue(errors(parse("author" to "a".repeat(61))).any { it.contains("author") })
    }

    @Test
    fun initialsComeFromTheFirstWords() {
        assertEquals("GC", PluginLooks.initials("Git control (Termux)"))
        assertEquals("TE", PluginLooks.initials("Termux"))
        assertEquals("?", PluginLooks.initials("!!!"))
    }

    @Test
    fun anIconWinsOverInitialsAndAnAccentGivesTheColour() {
        val red = PluginLooks.of("a.b.c", "Thing", "X", "#FF0000")
        assertEquals("X", red.text)
        assertEquals(0xFFFF0000, red.color)
        assertEquals(0xFFFFFFFF, red.onColor)
        assertEquals(0xFF111111, PluginLooks.of("a.b.c", "Thing", "", "#FFFF00").onColor)
    }

    @Test
    fun withoutAnAccentTheColourIsTheSameEveryTime() {
        val one = PluginLooks.of("lily.termux", "Termux", "", "")
        assertEquals(one, PluginLooks.of("lily.termux", "Termux", "", ""))
        assertEquals("TE", one.text)
        assertTrue(one.color shr 24 == 0xFFL)
    }
}
