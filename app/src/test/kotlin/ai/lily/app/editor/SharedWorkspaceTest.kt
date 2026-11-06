package ai.lily.app.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Moving to the shared folder must never lose or overwrite what the person already has there. */
class SharedWorkspaceTest {
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
        assertEquals(2 to 0, SharedWorkspace.copyMissing(from, to))
        assertEquals("b", File(to, "app/src/b.kt").readText())
        assertTrue(File(from, "a.txt").exists())
    }

    @Test
    fun neverOverwritesAFileThatIsAlreadyShared() {
        val from = tmp.newFolder("private")
        val to = tmp.newFolder("shared")
        file(from, "a.txt", "old")
        file(to, "a.txt", "newer")
        assertEquals(0 to 0, SharedWorkspace.copyMissing(from, to))
        assertEquals("newer", File(to, "a.txt").readText())
    }

    @Test
    fun aMissingSourceCopiesNothing() {
        assertEquals(0 to 0, SharedWorkspace.copyMissing(File(tmp.root, "nope"), tmp.newFolder("shared")))
    }
}
