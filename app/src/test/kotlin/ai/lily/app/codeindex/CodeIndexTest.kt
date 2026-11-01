package ai.lily.app.codeindex

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeIndexTest {
    private val parser = "package x\n\nfun parseReply(text: String): List<Edit> {\n    return emptyList()\n}\n"

    private fun project() = FakeCodeFiles().apply {
        put("src/Parser.kt", parser)
        put("src/Other.kt", "fun somethingElse() = 1\n")
    }

    @Test
    fun newFilesAreIndexedAndFoundWithTheirLines() = runBlocking<Unit> {
        val index = CodeIndex()

        val result = index.sync(project())

        assertEquals(SyncResult(added = 2, updated = 0, removed = 0, unchanged = 0, skipped = 0, truncated = false), result)
        val hit = index.search("parse the reply").single()
        assertEquals("src/Parser.kt", hit.path)
        assertEquals(1, hit.startLine)
        assertEquals(5, hit.endLine)
        assertEquals(2, hit.matched)
        assertTrue(hit.text.contains("fun parseReply"))
    }

    @Test
    fun aFilesNameCountsAsWords() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply { put("src/WorkspaceOps.kt", "val value = 1\n") }
        val index = CodeIndex()
        index.sync(files)

        assertEquals("src/WorkspaceOps.kt", index.search("WorkspaceOps").single().path)
        assertEquals("src/WorkspaceOps.kt", index.search("workspace ops").single().path)
    }

    @Test
    fun aSecondSyncOfAnUnchangedProjectReadsNothing() = runBlocking<Unit> {
        val files = project()
        val index = CodeIndex()
        index.sync(files)
        files.reads.clear()

        val result = index.sync(files)

        assertEquals(SyncResult(0, 0, 0, 2, 0, false), result)
        assertTrue(files.reads.isEmpty())
    }

    @Test
    fun aChangedFileIsReadAgainAndItsOldWordsAreForgotten() = runBlocking<Unit> {
        val files = project()
        val index = CodeIndex()
        index.sync(files)

        files.put("src/Parser.kt", "fun renamed() {}\n", modified = 2L)
        val result = index.sync(files)

        assertEquals(1, result.updated)
        assertEquals(1, result.unchanged)
        assertTrue(index.search("parseReply").isEmpty())
        assertEquals("src/Parser.kt", index.search("renamed").single().path)
    }

    @Test
    fun aFileSavedWithTheSameTextIsReadButNotCutAgain() = runBlocking<Unit> {
        val files = project()
        val index = CodeIndex()
        index.sync(files)
        val before = index.stats()
        files.reads.clear()

        files.touch("src/Parser.kt", 99L)
        val result = index.sync(files)

        assertEquals(listOf("src/Parser.kt"), files.reads)
        assertEquals(2, result.unchanged)
        assertEquals(0, result.updated)
        assertEquals(before, index.stats())
        assertEquals(1, index.search("parseReply").size)
        // The new stamp was remembered, so the next sync does not read it again.
        files.reads.clear()
        index.sync(files)
        assertTrue(files.reads.isEmpty())
    }

    @Test
    fun aDeletedFileLeavesTheIndex() = runBlocking<Unit> {
        val files = project()
        val index = CodeIndex()
        index.sync(files)

        files.remove("src/Parser.kt")
        val result = index.sync(files)

        assertEquals(1, result.removed)
        assertTrue(index.search("parseReply").isEmpty())
        assertEquals(CodeIndexStats(files = 1, chunks = 1), index.stats())
    }

    @Test
    fun excludedFilesAreNotReturned() = runBlocking<Unit> {
        val index = CodeIndex()
        index.sync(project())

        assertTrue(index.search("parseReply", exclude = setOf("src/Parser.kt")).isEmpty())
    }

    @Test
    fun blankBinaryAndMinifiedFilesAreSkippedAndNotReadAgain() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("good/Good.kt", "fun good() {}\n")
            put("a/blank.txt", "   \n")
            put("b/min.js", "x".repeat(3000))
            put("c/bin.dat", "ab\u0000cd")
        }
        val index = CodeIndex()

        val first = index.sync(files)
        assertEquals(1, first.added)
        assertEquals(3, first.skipped)
        assertEquals(4, files.reads.size)

        files.reads.clear()
        val second = index.sync(files)
        assertEquals(1, second.unchanged)
        assertEquals(3, second.skipped)
        assertTrue(files.reads.isEmpty())
    }

    @Test
    fun filesThatDoNotFitAreLeftOutAndTheResultSaysSo() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("one.kt", "fun one() {}\n")
            put("two.kt", "fun two() {}\n")
        }
        val index = CodeIndex(maxChunks = 1)

        val result = index.sync(files)

        assertEquals(1, result.added)
        assertEquals(1, result.skipped)
        assertTrue(result.truncated)
        assertEquals(1, index.stats().chunks)
    }

    @Test
    fun aTooLongFileListIsReportedAsTruncated() = runBlocking<Unit> {
        val files = project().apply { truncated = true }

        assertTrue(CodeIndex().sync(files).truncated)
    }

    @Test
    fun updateIndexesOneFileAtOnceAndTheNextSyncOnlyRefreshesIt() = runBlocking<Unit> {
        val index = CodeIndex()
        index.update("x/New.kt", "fun brandNew() {}\n")
        assertEquals("x/New.kt", index.search("brandNew").single().path)

        val files = FakeCodeFiles().apply { put("x/New.kt", "fun brandNew() {}\n") }
        val result = index.sync(files)

        assertEquals(0, result.added)
        assertEquals(0, result.updated)
        assertEquals(1, result.unchanged)
        assertEquals(listOf("x/New.kt"), files.reads)
        assertEquals(1, index.search("brandNew").size)
    }

    @Test
    fun updateWithNothingRemovesTheFile() = runBlocking<Unit> {
        val index = CodeIndex()
        index.update("x/New.kt", "fun brandNew() {}\n")

        index.update("x/New.kt", null)

        assertTrue(index.search("brandNew").isEmpty())
        assertEquals(0, index.stats().files)
    }

    @Test
    fun aFileTheDiskNoLongerHasIsDroppedEvenIfItCameFromUpdate() = runBlocking<Unit> {
        val index = CodeIndex()
        index.update("x/New.kt", "fun brandNew() {}\n")

        val result = index.sync(FakeCodeFiles())

        assertEquals(1, result.removed)
        assertTrue(index.search("brandNew").isEmpty())
    }

    @Test
    fun twoFilesWithTheSameTextStayIndependent() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("a/Same.kt", "fun twin() {}\n")
            put("b/Same.kt", "fun twin() {}\n")
        }
        val index = CodeIndex()
        index.sync(files)
        assertEquals(setOf("a/Same.kt", "b/Same.kt"), index.search("twin").map { it.path }.toSet())

        files.remove("b/Same.kt")
        index.sync(files)

        assertEquals(listOf("a/Same.kt"), index.search("twin").map { it.path })
    }

    @Test
    fun aLongFileIsSplitAndAHitPointsAtTheRightLines() = runBlocking<Unit> {
        val text = (1..300).joinToString("\n") { "val v$it = $it" }
        val files = FakeCodeFiles().apply { put("big/Lines.kt", text) }
        val index = CodeIndex()
        index.sync(files)

        assertTrue(index.stats().chunks > 1)
        val hits = index.search("v250", limit = 5)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { 250 in it.startLine..it.endLine })
    }

    @Test
    fun aTermThatAppearsMoreOftenInASmallerChunkScoresHigher() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("a.kt", "needle needle needle\n")
            put("b.kt", "needle plus a lot of other words that make this chunk much longer than the first one\n")
        }
        val index = CodeIndex()
        index.sync(files)

        assertEquals(listOf("a.kt", "b.kt"), index.search("needle").map { it.path })
    }

    @Test
    fun emptyQueriesAndEmptyIndexesGiveNothing() = runBlocking<Unit> {
        val index = CodeIndex()
        assertTrue(index.search("anything").isEmpty())

        index.sync(project())
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("please fix the").isEmpty())
        assertTrue(index.search("zzzznotthere").isEmpty())
    }
}
