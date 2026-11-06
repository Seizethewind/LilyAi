package ai.lily.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Moving to the chosen folder must never lose or overwrite what the person already has there. */
class WorkspaceMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, path: String, text: String) = File(root, path).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    @Test
    fun copiesMissingFilesIncludingNestedOnesAndKeepsTheOriginals() {
        val from = tmp.newFolder("private")
        val to = tmp.newFolder("shared")
        file(from, "a.txt", "a")
        file(from, "app/src/b.kt", "b")
        assertEquals(WorkspaceMigration.Result(2, 0), WorkspaceMigration.copyMissing(from, to))
        assertEquals("b", File(to, "app/src/b.kt").readText())
        assertTrue(File(from, "a.txt").exists())
    }

    @Test
    fun neverOverwritesAFileThatIsAlreadyThere() {
        val from = tmp.newFolder("private")
        val to = tmp.newFolder("shared")
        file(from, "a.txt", "old")
        file(to, "a.txt", "newer")
        assertEquals(WorkspaceMigration.Result(0, 0), WorkspaceMigration.copyMissing(from, to))
        assertEquals("newer", File(to, "a.txt").readText())
    }

    @Test
    fun aMissingSourceCopiesNothing() {
        assertEquals(WorkspaceMigration.Result(0, 0), WorkspaceMigration.copyMissing(File(tmp.root, "nope"), tmp.newFolder("shared")))
    }

    @Test
    fun aDestinationInsideTheSourceCopiesNothing() {
        val from = tmp.newFolder("private")
        file(from, "a.txt", "a")
        val to = File(from, "inner")
        assertEquals(WorkspaceMigration.Result(0, 0), WorkspaceMigration.copyMissing(from, to))
        assertFalse(File(to, "a.txt").exists())
    }

    @Test
    fun sameFolderSeesThroughTrailingPartsOfThePath() {
        val dir = tmp.newFolder("w")
        assertTrue(WorkspaceMigration.sameFolder(dir, File(dir, "sub/..")))
        assertFalse(WorkspaceMigration.sameFolder(dir, tmp.newFolder("other")))
    }

    @Test
    fun noteSaysWhatHappenedAndStaysQuietWhenNothingWasCopied() {
        val to = File("/docs/lilyai")
        assertNull(WorkspaceMigration.note(WorkspaceMigration.Result(0, 0), to))
        assertEquals("Copied 3 files to /docs/lilyai", WorkspaceMigration.note(WorkspaceMigration.Result(3, 0), to))
        assertTrue(WorkspaceMigration.note(WorkspaceMigration.Result(1, 2), to)!!.contains("2 could not be copied"))
    }
}
