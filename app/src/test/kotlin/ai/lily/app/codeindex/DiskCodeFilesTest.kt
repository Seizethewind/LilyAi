package ai.lily.app.codeindex

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real files in a temporary folder. */
class DiskCodeFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun put(root: File, rel: String, text: String) {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    private fun workspace(): File {
        val ws = tmp.newFolder("ws")
        put(ws, "src/Main.kt", "fun main() {}\n")
        put(ws, "README", "hello\n")
        put(ws, "crlf.txt", "a\r\nb\r\n")
        put(ws, "node_modules/lib/index.js", "x")
        put(ws, "build/out.txt", "y")
        put(ws, ".git/config", "z")
        put(ws, "logo.png", "not really a picture")
        put(ws, "big.txt", "a".repeat(200_001))
        File(ws, "empty.txt").createNewFile()
        return ws
    }

    @Test
    fun onlyWorthwhileFilesAreListed() = runBlocking<Unit> {
        val stamps = DiskCodeFiles(workspace()).stamps()

        assertEquals(setOf("src/Main.kt", "README", "crlf.txt"), stamps.files.keys)
        assertEquals(false, stamps.truncated)
    }

    @Test
    fun aStampHoldsTheSizeAndTheModifiedTime() = runBlocking<Unit> {
        val ws = workspace()
        val main = File(ws, "src/Main.kt")
        assertTrue(main.setLastModified(5_000_000L))

        val stamp = DiskCodeFiles(ws).stamps().files.getValue("src/Main.kt")

        assertEquals(FileStamp(main.length(), 5_000_000L), stamp)
    }

    @Test
    fun aSavedChangeChangesTheStamp() = runBlocking<Unit> {
        val ws = workspace()
        val files = DiskCodeFiles(ws)
        File(ws, "src/Main.kt").setLastModified(5_000_000L)
        val before = files.stamps().files.getValue("src/Main.kt")

        File(ws, "src/Main.kt").writeText("fun main() { println(1) }\n")
        File(ws, "src/Main.kt").setLastModified(6_000_000L)
        val after = files.stamps().files.getValue("src/Main.kt")

        assertTrue(before != after)
    }

    @Test
    fun windowsLineEndingsBecomeNewlines() = runBlocking<Unit> {
        assertEquals("a\nb\n", DiskCodeFiles(workspace()).read("crlf.txt"))
    }

    @Test
    fun pathsOutsideTheWorkspaceFoldersAndMissingFilesCannotBeRead() = runBlocking<Unit> {
        val ws = workspace()
        File(tmp.root, "outside.txt").writeText("secret")
        val files = DiskCodeFiles(ws)

        assertNull(files.read("../outside.txt"))
        assertNull(files.read("src"))
        assertNull(files.read("missing.kt"))
        assertEquals("fun main() {}\n", files.read("src/Main.kt"))
    }

    @Test
    fun aFileThatGrewPastTheLimitIsNotRead() = runBlocking<Unit> {
        val files = DiskCodeFiles(workspace())

        assertNull(files.read("big.txt"))
        assertNull(files.read("empty.txt"))
    }

    @Test
    fun theIndexWorksOnRealFilesEndToEnd() = runBlocking<Unit> {
        val ws = workspace()
        put(ws, "src/Parser.kt", "fun parseReply() = 1\n")
        val index = CodeIndex()

        val first = index.sync(DiskCodeFiles(ws))
        assertEquals(4, first.added) // Main.kt, README, crlf.txt, Parser.kt
        assertEquals("src/Parser.kt", index.search("parseReply").single().path)

        File(ws, "src/Parser.kt").writeText("fun renamedThing() = 2\n")
        File(ws, "src/Parser.kt").setLastModified(9_000_000L)
        val second = index.sync(DiskCodeFiles(ws))

        assertEquals(1, second.updated)
        assertTrue(index.search("parseReply").isEmpty())
        assertEquals("src/Parser.kt", index.search("renamedThing").single().path)
    }
}
