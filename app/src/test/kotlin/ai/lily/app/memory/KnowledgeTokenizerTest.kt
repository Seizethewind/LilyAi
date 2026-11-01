package ai.lily.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTokenizerTest {
    @Test
    fun wordsAreLowerCaseLettersAndDigits() {
        assertEquals(listOf("hello", "world", "it", "s", "42", "fold"), KnowledgeTokenizer.words("Hello, World! It's 42-fold."))
    }

    @Test
    fun wordsInOtherAlphabetsAreKeptWhole() {
        assertEquals(listOf("café", "ünïcode", "日本語"), KnowledgeTokenizer.words("Café Ünïcode 日本語"))
        // An accent written as a separate mark after the letter stays with its letter.
        assertEquals(listOf("café"), KnowledgeTokenizer.words("Café"))
    }

    @Test
    fun queryTermsDropOneLetterEnglishWordsAndRepeats() {
        assertEquals(listOf("go", "日"), KnowledgeTokenizer.queryTerms("a I x go Go 日"))
    }

    @Test
    fun queryTermsKeepTheOrderTheyWereTyped() {
        assertEquals(listOf("tomatoes", "sun", "water"), KnowledgeTokenizer.queryTerms("Tomatoes, SUN & water"))
    }

    @Test
    fun aLongQueryIsCutShort() {
        val query = (1..20).joinToString(" ") { "word$it" }
        assertEquals(KnowledgeTokenizer.MAX_QUERY_TERMS, KnowledgeTokenizer.queryTerms(query).size)
    }

    @Test
    fun punctuationAndBlanksGiveNoTerms() {
        assertTrue(KnowledgeTokenizer.queryTerms("").isEmpty())
        assertTrue(KnowledgeTokenizer.queryTerms("  ?! -- % ").isEmpty())
    }
}
