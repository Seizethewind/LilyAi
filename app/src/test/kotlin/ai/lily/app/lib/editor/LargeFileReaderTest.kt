package ai.lily.app.lib.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LargeFileReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(text: String): File = tmp.newFile().also { it.writeText(text) }

    private fun reader(file: File, every: Int = 3): LargeFileReader = LargeFileReader(file, every).also { assertTrue(it.buildIndex()) }

    private fun numbered(count: Int) = (0 until count).joinToString("\n") { "line$it" } + "\n"

    @Test
    fun linesAreCountedTheWayAnEditorWouldCountThem() {
        val cases = listOf(
            "" to 0, "\n" to 1, "a" to 1, "a\n" to 1, "a\nb" to 2, "a\nb\n" to 2, "\n\n" to 2, "a\n\nb\n" to 3,
        )
        for ((text, expected) in cases) {
            assertEquals(text, expected, reader(write(text)).lineCount)
        }
    }

    @Test
    fun theSizeIsRecorded() {
        val f = write(numbered(50))

        assertEquals(f.length(), reader(f).sizeBytes)
    }

    @Test
    fun linesCanBeReadFromAnywhereAcrossTheRememberedPlaces() {
        val r = reader(write(numbered(10)), every = 3)
        val all = (0 until 10).map { "line$it" }

        assertEquals(all, r.readLines(0, 10))
        assertEquals(listOf("line4", "line5", "line6"), r.readLines(4, 3))
        assertEquals(listOf("line9"), r.readLines(9, 5))
        assertEquals(listOf("line0", "line1"), r.readLines(-3, 2))
        assertTrue(r.readLines(10, 5).isEmpty())
        assertTrue(r.readLines(0, 0).isEmpty())
        for (start in 0 until 10) assertEquals(all.drop(start).take(4), r.readLines(start, 4))
    }

    @Test
    fun aLastLineWithoutANewlineIsStillALine() {
        val r = reader(write("a\nb"))

        assertEquals(listOf("b"), r.readLines(1, 1))
    }

    @Test
    fun windowsLineEndingsAreDropped() {
        assertEquals(listOf("a", "b", "c"), reader(write("a\r\nb\r\nc")).readLines(0, 3))
    }

    @Test
    fun textInAnyAlphabetSurvives() {
        val lines = listOf("h\u00e9llo w\u00f6rld", "\u65e5\u672c\u8a9e \u2713", "emoji \uD83D\uDE00 end")
        val r = reader(write(lines.joinToString("\n") + "\n"))

        assertEquals(3, r.lineCount)
        assertEquals(lines, r.readLines(0, 3))
        assertEquals(listOf(lines[1]), r.readLines(1, 1))
    }

    @Test
    fun aVeryLongLineIsCutAndSaysHowMuchWasLeftOut() {
        val r = reader(write("x".repeat(50_000) + "\nnext\n"))

        val (first, second) = r.readLines(0, 2)

        assertEquals("x".repeat(20_000) + " \u2026 (+30000 bytes)", first)
        assertEquals("next", second)
    }

    @Test
    fun aCutNeverLeavesAHalfCharacterBehind() {
        val r = reader(write("\u65e5".repeat(10_000) + "\n"))

        val line = r.readLines(0, 1).single()

        assertFalse(line.contains('\uFFFD'))
        assertTrue(line.startsWith("\u65e5".repeat(6_666) + " \u2026 (+10000 bytes)"))
    }

    @Test
    fun aLineLongerThanTheReadBufferDoesNotUpsetTheLinesAfterIt() {
        val r = reader(write("x".repeat(200_000) + "\nend"))

        assertEquals(2, r.lineCount)
        assertEquals(listOf("end"), r.readLines(1, 1))
    }

    @Test
    fun emptyFilesHaveNoLines() {
        val r = reader(write(""))

        assertEquals(0, r.lineCount)
        assertTrue(r.readLines(0, 5).isEmpty())
        assertNull(r.findNext("a", 0))
        assertNull(r.findPrevious("a", 0))
    }

    private fun needles(): LargeFileReader {
        val text = (0 until 5_000).joinToString("\n") { if (it == 100 || it == 3_700) "line $it has a Needle in it" else "line $it" } + "\n"
        return reader(write(text), every = 1_000)
    }

    @Test
    fun findingTheNextLineWrapsAroundTheEnd() {
        val r = needles()

        assertEquals(100, r.findNext("needle", 0))
        assertEquals(100, r.findNext("needle", 100))
        assertEquals(3_700, r.findNext("needle", 101))
        assertEquals(100, r.findNext("needle", 3_701))
        assertEquals(100, r.findNext("needle", 4_999))
        assertEquals(100, r.findNext("needle", 6_000))
    }

    @Test
    fun searchCanBeCaseSensitive() {
        val r = needles()

        assertNull(r.findNext("needle", 0, ignoreCase = false))
        assertEquals(100, r.findNext("Needle", 0, ignoreCase = false))
    }

    @Test
    fun nothingIsFoundForAnEmptyOrMissingQuery() {
        val r = needles()

        assertNull(r.findNext("", 0))
        assertNull(r.findNext("zzz", 0))
        assertNull(r.findPrevious("", 10))
        assertNull(r.findPrevious("zzz", 10))
    }

    @Test
    fun findingThePreviousLineWrapsAroundTheStart() {
        val r = needles()

        assertEquals(100, r.findPrevious("needle", 3_700))
        assertEquals(3_700, r.findPrevious("needle", 3_701))
        assertEquals(3_700, r.findPrevious("needle", 100))
        assertEquals(3_700, r.findPrevious("needle", 5_000))
        assertEquals(3_700, r.findPrevious("needle", 0))
    }

    @Test
    fun aCancelledIndexReturnsFalseAndAnUnbuiltReaderRefusesToRead() {
        val r = LargeFileReader(write(numbered(10)))

        assertFalse(r.buildIndex(cancelled = { true }))
        try {
            r.readLines(0, 1)
            fail("should have thrown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("index"))
        }
    }

    @Test
    fun aCancelledRebuildLeavesTheOldIndexWorking() {
        val r = reader(write(numbered(10)))

        assertFalse(r.buildIndex(cancelled = { true }))

        assertEquals(10, r.lineCount)
        assertEquals(listOf("line3"), r.readLines(3, 1))
    }

    @Test
    fun aCancelledSearchGivesUpInsteadOfFindingALateMatch() {
        // The only match is at line 4000, far past the first check for cancellation, so a search that ignored it would find it.
        val text = (0 until 5_000).joinToString("\n") { if (it == 4_000) "needle" else "line $it" } + "\n"
        val r = reader(write(text), every = 1_000)

        assertEquals(4_000, r.findNext("needle", 0))
        assertNull(r.findNext("needle", 0, cancelled = { true }))
        assertEquals(4_000, r.findPrevious("needle", 4_999))
        assertNull(r.findPrevious("needle", 4_999, cancelled = { true }))
    }

    @Test
    fun progressIsReportedWhileReading() {
        val f = write(numbered(30_000))
        val seen = ArrayList<Long>()

        assertTrue(LargeFileReader(f).buildIndex(onProgress = { seen += it }))

        assertTrue(seen.size > 1)
        assertEquals(seen.sorted(), seen)
        assertEquals(f.length(), seen.last())
    }

    @Test
    fun aHundredThousandLinesAreReadAndFoundQuickly() {
        val text = (0 until 100_000).joinToString("\n") { "row $it: ${"abcdefghij".repeat(4)}" } + "\n"
        val f = write(text)
        val r = LargeFileReader(f)
        assertTrue(r.buildIndex())

        assertEquals(100_000, r.lineCount)
        assertEquals(f.length(), r.sizeBytes)
        assertEquals((99_990 until 100_000).map { "row $it: ${"abcdefghij".repeat(4)}" }, r.readLines(99_990, 20))
        assertEquals(77_777, r.findNext("row 77777:", 0))
        assertEquals(77_777, r.findPrevious("row 77777:", 100_000))
    }
}

class LargeFileWindowTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun window(lines: Int, size: Int = 5): LargeFileWindow {
        val f = tmp.newFile()
        f.writeText((0 until lines).joinToString("\n") { "line$it" } + if (lines > 0) "\n" else "")
        val r = LargeFileReader(f, 4)
        assertTrue(r.buildIndex())
        return LargeFileWindow(r, size)
    }

    @Test
    fun aPageHoldsTheRequestedLines() {
        val w = window(12)

        w.load(0)

        assertEquals(0, w.firstLine)
        assertEquals((0..4).map { "line$it" }, w.lines)
        assertEquals(12, w.lineCount)
    }

    @Test
    fun pagingStopsAtTheEndAndAtTheStart() {
        val w = window(12)
        w.load(0)

        w.page(1)
        assertEquals(5, w.firstLine)
        w.page(1)
        assertEquals(7, w.firstLine)
        assertEquals((7..11).map { "line$it" }, w.lines)
        w.page(-1)
        assertEquals(2, w.firstLine)
        w.load(-9)
        assertEquals(0, w.firstLine)
        w.load(100)
        assertEquals(7, w.firstLine)
    }

    @Test
    fun goingToALineShowsItNearTheTop() {
        val w = window(12)

        w.goToLine(8)
        assertEquals(5, w.firstLine)
        assertTrue("line8" in w.lines)
        w.goToLine(0)
        assertEquals(0, w.firstLine)
        w.goToLine(11)
        assertEquals(7, w.firstLine)
        assertTrue("line11" in w.lines)
    }

    @Test
    fun aFileShorterThanAPageFitsInOne() {
        val w = window(3)

        w.load(2)

        assertEquals(0, w.firstLine)
        assertEquals(listOf("line0", "line1", "line2"), w.lines)
    }

    @Test
    fun anEmptyFileGivesAnEmptyPage() {
        val w = window(0)

        w.load(0)

        assertEquals(0, w.firstLine)
        assertTrue(w.lines.isEmpty())
    }

    @Test
    fun aPageNeedsAtLeastOneLine() {
        try {
            window(3, size = 0)
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("at least one line"))
        }
    }
}

class FormatBytesTest {
    @Test
    fun sizesAreWrittenForPeople() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1_048_576))
        assertEquals("1.4 MB", formatBytes(1_500_000))
        assertEquals("512.0 MB", formatBytes(512L * 1024 * 1024))
        assertEquals("3.0 GB", formatBytes(3L * 1024 * 1024 * 1024))
    }
}
