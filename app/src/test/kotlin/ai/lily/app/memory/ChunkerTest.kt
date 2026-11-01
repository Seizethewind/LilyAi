package ai.lily.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChunkerTest {
    /** Lines of exactly 19 characters, so each is 5 tokens (19 characters and a newline, at four characters to a token). */
    private fun lines(n: Int) = (1..n).joinToString("\n") { "L${"%02d".format(it)} ".padEnd(19, 'x') }

    private fun ranges(chunks: List<Chunk>) = chunks.map { it.startLine to it.endLine }

    @Test
    fun tokenEstimateIsAboutFourCharactersToAToken() {
        assertEquals(0, KnowledgeTokens.estimate(""))
        assertEquals(1, KnowledgeTokens.estimate("abcd"))
        assertEquals(2, KnowledgeTokens.estimate("abcde"))
    }

    @Test
    fun theSettingsAreInTheSignature() {
        assertEquals("v1/400/15", Chunker.signature())
        assertEquals("v1/20/25", Chunker.signature(20, 25))
    }

    @Test
    fun noTextMeansNoChunks() {
        assertTrue(Chunker.split("").isEmpty())
        assertTrue(Chunker.split("\n\n  \n").isEmpty())
    }

    @Test
    fun shortTextIsOneChunkWithItsLineRange() {
        val chunks = Chunker.split("hello\nworld")
        assertEquals(1, chunks.size)
        assertEquals(Chunk(0, 1, 2, "hello\nworld", KnowledgeTokens.estimate("hello\nworld")), chunks[0])
    }

    @Test
    fun aFinalNewlineIsNotAnExtraLine() {
        assertEquals(2, Chunker.split("a\nb\n").single().endLine)
    }

    @Test
    fun windowsLineEndingsAreTreatedAsOneLineBreak() {
        val chunk = Chunker.split("a\r\nb\rc").single()
        assertEquals("a\nb\nc", chunk.text)
        assertEquals(3, chunk.endLine)
    }

    @Test
    fun neighbouringChunksShareTheirOverlap() {
        val chunks = Chunker.split(lines(10), targetTokens = 20, overlapPercent = 25)
        assertEquals(listOf(1 to 4, 4 to 7, 7 to 10), ranges(chunks))
        assertEquals(listOf(0, 1, 2), chunks.map { it.ordinal })
        assertTrue(chunks.all { it.tokens <= 20 })
        assertTrue(chunks[1].text.startsWith("L04"))
        assertTrue(chunks[0].text.endsWith("L04 xxxxxxxxxxxxxxx"))
    }

    @Test
    fun noOverlapMeansTheChunksDoNotShareLines() {
        assertEquals(listOf(1 to 4, 5 to 8, 9 to 10), ranges(Chunker.split(lines(10), targetTokens = 20, overlapPercent = 0)))
    }

    @Test
    fun anOverlapSmallerThanOneLineIsSkippedButTheTextStillMovesOn() {
        // 15 percent of 20 tokens is 3 tokens, and every line is 5.
        assertEquals(listOf(1 to 4, 5 to 8, 9 to 10), ranges(Chunker.split(lines(10), targetTokens = 20, overlapPercent = 15)))
    }

    @Test
    fun theDefaultsAre400TokensWith15PercentOverlap() {
        val chunks = Chunker.split(lines(300))
        assertEquals(listOf(1 to 80, 69 to 148, 137 to 216, 205 to 284, 273 to 300), ranges(chunks))
        // 12 lines of 5 tokens is 60 tokens, which is 15 percent of 400.
        assertEquals(12, chunks[0].endLine - chunks[1].startLine + 1)
        assertTrue(chunks.all { it.tokens <= Chunker.TARGET_TOKENS })
    }

    @Test
    fun everyLineIsInAtLeastOneChunk() {
        val chunks = Chunker.split(lines(137), targetTokens = 30, overlapPercent = 20)
        val covered = chunks.flatMap { it.startLine..it.endLine }.toSet()
        assertEquals((1..137).toSet(), covered)
        chunks.zipWithNext().forEach { (a, b) ->
            assertTrue("chunks must move forward", b.startLine > a.startLine)
            assertTrue("no gap between chunks", b.startLine <= a.endLine + 1)
        }
    }

    @Test
    fun aLongLineIsCutAtSpacesAndKeepsItsLineNumber() {
        val line = "lorem ".repeat(100).trim()
        val chunks = Chunker.split(line, targetTokens = 20, overlapPercent = 0)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.startLine == 1 && it.endLine == 1 })
        assertTrue(chunks.all { it.tokens <= 20 })
        assertEquals(line, chunks.joinToString("") { it.text })
        assertTrue(chunks.dropLast(1).all { it.text.endsWith(" ") })
    }

    @Test
    fun aLongLineWithoutSpacesIsCutAtAHardLimit() {
        val line = "x".repeat(500)
        val chunks = Chunker.split(line, targetTokens = 20, overlapPercent = 0)
        assertEquals(7, chunks.size)
        assertTrue(chunks.all { it.text.length <= 79 })
        assertEquals(line, chunks.joinToString("") { it.text })
    }

    @Test
    fun aCharacterMadeOfTwoPartsIsNeverCutInHalf() {
        val line = "😀".repeat(60)
        val chunks = Chunker.split(line, targetTokens = 20, overlapPercent = 0)
        assertTrue(chunks.size > 1)
        assertEquals(line, chunks.joinToString("") { it.text })
        chunks.forEach {
            assertFalse(Character.isHighSurrogate(it.text.last()))
            assertFalse(Character.isLowSurrogate(it.text.first()))
        }
    }

    @Test
    fun aChunkOfOnlyBlankLinesIsDroppedAndNumberingHasNoGap() {
        val chunks = Chunker.split("\n".repeat(30) + "real text", targetTokens = 20, overlapPercent = 15)
        val chunk = chunks.single()
        assertEquals(0, chunk.ordinal)
        assertTrue(chunk.text.contains("real text"))
        assertEquals(31, chunk.endLine)
    }

    @Test
    fun badSettingsAreRefused() {
        for (bad in listOf<() -> Unit>({ Chunker.split("x", targetTokens = 5) }, { Chunker.split("x", overlapPercent = 60) })) {
            try {
                bad()
                fail("should have been refused")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }
}
