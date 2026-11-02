package ai.lily.app.lib.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class FontChoiceTest {
    @Test
    fun everyBuiltinFontSurvivesBeingSavedAndLoaded() {
        for (b in BuiltinFont.values()) {
            val choice = FontChoice.Builtin(b)
            assertEquals(choice, FontChoice.parse(choice.id))
        }
        assertEquals(FontChoice.Builtin(BuiltinFont.SERIF), FontChoice.parse("builtin:serif"))
    }

    @Test
    fun anAddedFontSurvivesBeingSavedAndLoaded() {
        val choice = FontChoice.Custom("My Font (2).ttf")

        assertEquals("file:My Font (2).ttf", choice.id)
        assertEquals(choice, FontChoice.parse(choice.id))
    }

    @Test
    fun anythingUnknownOrUnsafeFallsBackToTheDefault() {
        listOf(null, "", "garbage", "builtin:comic", "builtin:", "file:", "file:../evil.ttf", "file:a/b.ttf", "file:noextension", "file:x.txt")
            .forEach { assertEquals(it, FontChoice.DEFAULT, FontChoice.parse(it)) }
    }

    @Test
    fun theDefaultIsMonospaceAndItsIdMatches() {
        assertEquals(FontChoice.Builtin(BuiltinFont.MONOSPACE), FontChoice.DEFAULT)
        assertEquals(FontChoice.DEFAULT.id, FontChoice.DEFAULT_ID)
        assertEquals(FontChoice.DEFAULT_ID, EditorSettings().font)
    }
}
