package ai.lily.app.codeindex

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WorkspaceIndexTest {
    private fun project() = FakeCodeFiles().apply {
        put("src/Parser.kt", "fun parseReply(text: String): List<Edit> {\n    return emptyList()\n}\n")
        put("src/Applier.kt", "fun applyFileEdits(base: String, text: String): String = base\n")
        put("notes/Lunch.md", "pasta for lunch on friday\n")
    }

    @Test
    fun theFileTheRequestIsAboutIsFound() = runBlocking<Unit> {
        val ws = WorkspaceIndex(project())

        val found = ws.relatedTo("fix parseReply so it handles empty text")

        assertEquals(listOf("src/Parser.kt"), found.map { it.path })
        assertEquals(1..3, found.single().lines)
    }

    @Test
    fun aFileThatSharesOnlyOneWordOfALongRequestIsLeftOut() = runBlocking<Unit> {
        val ws = WorkspaceIndex(project())

        // Applier.kt contains "text" but nothing else from the request.
        val found = ws.relatedTo("fix parseReply so it handles empty text", exclude = setOf("src/Parser.kt"))

        assertTrue(found.isEmpty())
    }

    @Test
    fun aShortRequestMayMatchOnOneWord() = runBlocking<Unit> {
        val ws = WorkspaceIndex(project())

        assertEquals(listOf("src/Applier.kt"), ws.relatedTo("applyFileEdits").map { it.path })
    }

    @Test
    fun filesAlreadyShownAreNeverReturned() = runBlocking<Unit> {
        val ws = WorkspaceIndex(project())

        assertTrue(ws.relatedTo("parseReply", exclude = setOf("src/Parser.kt")).isEmpty())
    }

    @Test
    fun theNumberOfFilesIsLimitedAndTheBestComesFirst() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("one.kt", "alpha beta gamma alpha beta gamma\n")
            put("two.kt", "alpha beta gamma filler words here to make it longer than the first\n")
            put("three.kt", "alpha beta gamma other padding words here to make it longer than the first\n")
        }
        val ws = WorkspaceIndex(files)

        val found = ws.relatedTo("alpha beta gamma", maxFiles = 2)

        assertEquals(2, found.size)
        assertEquals("one.kt", found[0].path)
        assertTrue(found[0].score >= found[1].score)
        assertTrue(ws.relatedTo("alpha beta gamma", maxFiles = 0).isEmpty())
    }

    @Test
    fun changesToTheProjectAreSeenOnTheNextCall() = runBlocking<Unit> {
        val files = project()
        val ws = WorkspaceIndex(files)
        assertTrue(ws.relatedTo("streamChunks").isEmpty())

        files.put("src/Streaming.kt", "fun streamChunks() {}\n")

        assertEquals(listOf("src/Streaming.kt"), ws.relatedTo("streamChunks").map { it.path })
    }

    @Test
    fun ifTheProjectCannotBeListedTheIndexKeepsWhatItHad() = runBlocking<Unit> {
        val files = project()
        val ws = WorkspaceIndex(files)
        ws.relatedTo("parseReply")

        files.failure = IOException("disk gone")

        assertEquals(listOf("src/Parser.kt"), ws.relatedTo("parseReply").map { it.path })
    }

    @Test
    fun withNothingIndexedAndAFailingProjectTheAnswerIsEmptyNotAnError() = runBlocking<Unit> {
        val files = project().apply { failure = IOException("disk gone") }

        assertTrue(WorkspaceIndex(files).relatedTo("parseReply").isEmpty())
    }
}
