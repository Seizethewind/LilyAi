package ai.lily.app.codeindex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeTokenizerTest {
    @Test
    fun anIdentifierGivesItsWholeFormAndItsParts() {
        assertEquals(listOf("parsereply", "parse", "reply"), CodeTokenizer.terms("parseReply"))
        assertEquals(listOf("parsereply", "parse", "reply"), CodeTokenizer.terms("parse_reply"))
        assertEquals(listOf("parsereply", "parse", "reply"), CodeTokenizer.terms("ParseReply"))
    }

    @Test
    fun anAcronymBeforeAWordIsSplitOffIt() {
        assertEquals(listOf("httpserver", "http", "server"), CodeTokenizer.terms("HTTPServer"))
    }

    @Test
    fun singleCharactersAndPlainNumbersAreDropped() {
        assertTrue(CodeTokenizer.terms("x = 1 + 22").isEmpty())
        assertEquals(listOf("utf8"), CodeTokenizer.terms("utf8"))
        assertEquals(listOf("utf8parser", "utf8", "parser"), CodeTokenizer.terms("Utf8Parser"))
    }

    @Test
    fun repeatsAreKeptSoTheyCanBeCounted() {
        assertEquals(listOf("foo", "foo"), CodeTokenizer.terms("foo foo"))
    }

    @Test
    fun lettersOfOtherAlphabetsCount() {
        assertEquals(listOf("gr\u00f6\u00dfe"), CodeTokenizer.terms("gr\u00f6\u00dfe"))
    }

    @Test
    fun aQuestionLosesWordsThatOnlySayWhatToDo() {
        val terms = CodeTokenizer.queryTerms("Please fix the parseReply function in Parser.kt")

        assertEquals(listOf("parsereply", "parse", "reply", "parser", "kt"), terms)
    }

    @Test
    fun codeKeepsEverythingIncludingCommonWords() {
        assertTrue("the" in CodeTokenizer.terms("val the = 1"))
        assertFalse("the" in CodeTokenizer.queryTerms("val the = 1"))
    }
}
