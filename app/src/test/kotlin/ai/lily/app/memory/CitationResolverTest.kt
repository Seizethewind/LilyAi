package ai.lily.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationResolverTest {
    @Test
    fun markersThatMatchAnExcerptAreKept() {
        val r = CitationResolver.resolve("A fact [1]. Another [2].", available = 2)

        assertEquals("A fact [1]. Another [2].", r.text)
        assertEquals(listOf(1, 2), r.used)
    }

    @Test
    fun numbersThatWereNeverRetrievedAreRemovedWithTheirSpace() {
        val r = CitationResolver.resolve("Fact [1]. Invented [7]. Both [1, 9]. Zero [0].", available = 3)

        assertEquals("Fact [1]. Invented. Both [1]. Zero.", r.text)
        assertEquals(listOf(1), r.used)
    }

    @Test
    fun theRemainingNumbersAreRenumberedByFirstAppearance() {
        val r = CitationResolver.resolve("First [3]. Second [1]. Again [3]. Together [2, 1].", available = 3)

        assertEquals("First [1]. Second [2]. Again [1]. Together [2][3].", r.text)
        assertEquals(listOf(3, 1, 2), r.used)
    }

    @Test
    fun severalMarkersInARowAreEachChecked() {
        val r = CitationResolver.resolve("Claim [4][2][9].", available = 4)

        assertEquals("Claim [1][2].", r.text)
        assertEquals(listOf(4, 2), r.used)
    }

    @Test
    fun codeIndexingIsLeftAlone() {
        val text = "Use arr[2] or grid[0][1] here, see [1][2] and [5]."

        val r = CitationResolver.resolve(text, available = 2)

        assertEquals("Use arr[2] or grid[0][1] here, see [1][2] and.", r.text)
        assertEquals(listOf(1, 2), r.used)
    }

    @Test
    fun anAnswerWithNoMarkersIsUnchanged() {
        val r = CitationResolver.resolve("Nothing to cite.", available = 5)

        assertEquals("Nothing to cite.", r.text)
        assertTrue(r.used.isEmpty())
    }

    @Test
    fun withNoExcerptsEveryMarkerGoes() {
        val r = CitationResolver.resolve("Claim [1].", available = 0)

        assertEquals("Claim.", r.text)
        assertTrue(r.used.isEmpty())
    }

    @Test
    fun aHugeNumberIsJustInvalid() {
        val r = CitationResolver.resolve("Claim [99999999999999999999] and [1].", available = 1)

        assertEquals("Claim and [1].", r.text)
        assertEquals(listOf(1), r.used)
    }
}
