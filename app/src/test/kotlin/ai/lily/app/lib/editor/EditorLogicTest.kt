package ai.lily.app.lib.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EditorLogicTest {

    // ---- Highlighting ----

    @Test
    fun kotlinKeywordStringAndComment() {
        val spans = highlight("val x = \"hi\" // note", Lang.KOTLIN)
        assertTrue(spans.any { it.kind == TokenKind.KEYWORD && it.start == 0 && it.end == 3 })
        assertTrue(spans.any { it.kind == TokenKind.STRING })
        assertTrue(spans.any { it.kind == TokenKind.COMMENT })
    }

    @Test
    fun spansNeverOverlapAndStayInsideTheText() {
        val text = "fun main() {\n    println(\"a // not a comment\") // real\n    /* block */ val n = 0x1F\n}\n"
        val spans = highlight(text, Lang.KOTLIN)
        var end = 0
        for (s in spans) {
            assertTrue(s.start >= end)
            assertTrue(s.end > s.start)
            assertTrue(s.end <= text.length)
            end = s.end
        }
    }

    @Test
    fun jsonKeysAreProperties() {
        val spans = highlight("{\"a\": 1}", Lang.JSON)
        assertTrue(spans.any { it.kind == TokenKind.PROPERTY && it.start == 1 && it.end == 4 })
        assertTrue(spans.any { it.kind == TokenKind.NUMBER })
    }

    @Test
    fun scriptInsideHtmlIsColoured() {
        val spans = highlight("<script>var x = 1;</script>", Lang.HTML)
        assertTrue(spans.any { it.kind == TokenKind.KEYWORD && it.start == 8 && it.end == 11 })
        assertTrue(spans.any { it.kind == TokenKind.TAG })
    }

    @Test
    fun languageIsPickedFromTheFileName() {
        assertEquals(Lang.KOTLIN, languageFor("app/src/Main.kt"))
        assertEquals(Lang.PYTHON, languageFor("tool.PY"))
        assertEquals(Lang.PLAIN, languageFor("notes"))
    }

    // ---- Find and replace ----

    @Test
    fun findIgnoresCaseByDefault() {
        val re = buildFindRegex(FindOptions("foo"))
        assertEquals(3, findMatches("Foo foo FOO", re).size)
        val exact = buildFindRegex(FindOptions("foo", caseSensitive = true))
        assertEquals(1, findMatches("Foo foo FOO", exact).size)
    }

    @Test
    fun wholeWordSkipsPartsOfWords() {
        val re = buildFindRegex(FindOptions("cat", wholeWord = true))
        assertEquals(2, findMatches("cat concat cat", re).size)
    }

    @Test
    fun plainSearchTreatsSymbolsLiterally() {
        val re = buildFindRegex(FindOptions("a.b"))
        assertEquals(1, findMatches("a.b axb", re).size)
    }

    @Test
    fun badRegexThrows() {
        try {
            buildFindRegex(FindOptions("(", regex = true))
            fail("expected an invalid pattern error")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun replaceUsesGroupsOnlyInRegexMode() {
        val re = buildFindRegex(FindOptions("([a-z])(\\d)", regex = true))
        assertEquals("1a", replaceOne("a1 b2", re, IntRange(0, 1), "\$2\$1", true))
        val plain = buildFindRegex(FindOptions("x"))
        assertEquals("\$1", replaceOne("x", plain, IntRange(0, 0), "\$1", false))
        assertEquals("1a 2b", replaceEvery("a1 b2", re, "\$2\$1", true))
    }

    // ---- Undo ----

    @Test
    fun typingInARowIsOneUndoStep() {
        val h = UndoHistory("")
        h.record("a", 1, 1, now = 0)
        h.record("ab", 2, 2, now = 100)
        assertTrue(h.canUndo)
        assertEquals("", h.undo()?.text)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo)
        assertEquals("ab", h.redo()?.text)
    }

    @Test
    fun aBoundaryStartsANewStep() {
        val h = UndoHistory("one")
        h.record("two", 3, 3, now = 0, boundary = true)
        h.record("three", 5, 5, now = 10, boundary = true)
        assertEquals("two", h.undo()?.text)
        assertEquals("one", h.undo()?.text)
        assertNull(h.undo())
    }

    // ---- Text editing ----

    @Test
    fun enterKeepsTheIndentAndOpensABlock() {
        val old = TextFieldValue("if (x) {", TextRange(8))
        val typed = TextFieldValue("if (x) {\n", TextRange(9))
        val result = smartEdit(old, typed, Lang.KOTLIN, "    ")
        assertNotNull(result)
        assertEquals("if (x) {\n    ", result!!.text)
        assertEquals(TextRange(13), result.selection)
    }

    @Test
    fun openingBracketGetsItsPartner() {
        val result = smartEdit(
            TextFieldValue("foo", TextRange(3)),
            TextFieldValue("foo(", TextRange(4)),
            Lang.KOTLIN,
            "    ",
        )
        assertEquals("foo()", result!!.text)
        assertEquals(TextRange(4), result.selection)
    }

    @Test
    fun typingACloserStepsOverTheOneThatIsThere() {
        val result = smartEdit(
            TextFieldValue("foo()", TextRange(4)),
            TextFieldValue("foo())", TextRange(5)),
            Lang.KOTLIN,
            "    ",
        )
        assertEquals("foo()", result!!.text)
        assertEquals(TextRange(5), result.selection)
    }

    @Test
    fun plainTextIsLeftAlone() {
        val result = smartEdit(
            TextFieldValue("foo", TextRange(3)),
            TextFieldValue("foo(", TextRange(4)),
            Lang.PLAIN,
            "    ",
        )
        assertNull(result)
    }

    @Test
    fun indentAndUnindentTheSelectedLines() {
        val v = TextFieldValue("a\nb", TextRange(0, 3))
        val indented = shiftIndent(v, "  ", unindent = false)
        assertEquals("  a\n  b", indented.text)
        assertEquals("a\nb", shiftIndent(indented, "  ", unindent = true).text)
    }

    @Test
    fun tabWithNothingSelectedInsertsOneIndent() {
        val v = TextFieldValue("ab", TextRange(1))
        val result = shiftIndent(v, "    ", unindent = false)
        assertEquals("a    b", result.text)
        assertEquals(TextRange(5), result.selection)
    }

    @Test
    fun toggleCommentGoesBothWays() {
        val v = TextFieldValue("a\nb", TextRange(0, 3))
        val on = toggleLineComment(v, "//")
        assertEquals("// a\n// b", on.text)
        assertEquals("a\nb", toggleLineComment(on, "//").text)
    }

    @Test
    fun duplicateAndDeleteLine() {
        val v = TextFieldValue("one\ntwo", TextRange(1))
        assertEquals("one\none\ntwo", duplicateLines(v).text)
        assertEquals("two", deleteLines(v).text)
        val last = TextFieldValue("one\ntwo", TextRange(5))
        assertEquals("one", deleteLines(last).text)
    }

    @Test
    fun lineAndColumnAreOneBased() {
        assertEquals(Pair(2, 2), lineColumn("ab\ncd", 4))
        assertEquals(Pair(1, 1), lineColumn("", 0))
    }

    @Test
    fun indentIsDetectedFromTheFile() {
        assertEquals("  ", detectIndent("fun a() {\n  x\n}"))
        assertEquals("\t", detectIndent("a\n\tb"))
        assertEquals("    ", detectIndent(""))
    }

    @Test
    fun goToLineLandsOnTheLineStart() {
        val v = gotoLine(TextFieldValue("a\nbb\nccc"), 3)
        assertEquals(TextRange(5), v.selection)
        assertEquals(TextRange(5), gotoLine(TextFieldValue("a\nbb\nccc"), 99).selection)
    }
}
