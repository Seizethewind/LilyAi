package ai.lily.app.workers

import ai.lily.app.codeindex.FakeCodeFiles
import ai.lily.app.codeindex.WorkspaceIndex
import ai.lily.app.core.Task
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.ModelResponse
import ai.lily.app.tools.EditorBridge
import ai.lily.app.tools.OpenFile
import ai.lily.app.tools.ProposedEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The edit pipeline with a project index: which files the model is shown, and what the reply says about them. */
class EditPipelineRelatedTest {
    private class FakeBridge(private val open: List<OpenFile>, private val disk: Map<String, String>) : EditorBridge {
        val proposed = ArrayList<ProposedEdit>()

        override suspend fun openFiles(): List<OpenFile> = open

        override suspend fun listFiles(): List<String> = (open.map { it.path } + disk.keys).distinct().sorted()

        override suspend fun readFile(path: String): String? = open.firstOrNull { it.path == path }?.text ?: disk[path]

        override suspend fun propose(edits: List<ProposedEdit>): String {
            proposed += edits
            return "Proposed."
        }
    }

    private class RecordingGateway(private val reply: String) : ModelGateway {
        val requests = ArrayList<ModelRequest>()

        override suspend fun complete(request: ModelRequest): ModelResponse {
            requests += request
            return ModelResponse(reply)
        }
    }

    private val main = "fun main() { println(1) }\n"
    private val parser = "fun parseReply() = 1\n"

    private fun index(extra: FakeCodeFiles.() -> Unit = {}) = WorkspaceIndex(
        FakeCodeFiles().apply {
            put("Main.kt", main)
            put("Parser.kt", parser)
            put("Other.kt", "fun other() = 2\n")
            extra()
        },
    )

    private fun bridge(openParser: Boolean = false, extraDisk: Map<String, String> = emptyMap()): FakeBridge {
        val open = mutableListOf(OpenFile("Main.kt", main, active = true, dirty = false))
        if (openParser) open += OpenFile("Parser.kt", parser, active = false, dirty = false)
        return FakeBridge(open, mapOf("Parser.kt" to parser, "Other.kt" to "fun other() = 2\n") + extraDisk)
    }

    private fun task(text: String) = Task("t1", text, WorkerType.CODING)

    @Test
    fun aRelatedFileBeyondTheOpenTabsIsShownToTheModel() = runBlocking<Unit> {
        val gateway = RecordingGateway("Nothing to change.")

        val result = EditPipeline(gateway, bridge(), "m", index()).run(task("fix parseReply so empty text works"))

        val prompt = gateway.requests.single().prompt
        assertTrue(prompt.contains("=== Main.kt (the file the user is looking at) ==="))
        assertTrue(prompt.contains("=== Parser.kt ==="))
        assertTrue(prompt.contains("fun parseReply() = 1"))
        assertFalse(prompt.contains("=== Other.kt ==="))
        assertEquals("Nothing to change.", result.output)
    }

    @Test
    fun withoutAnIndexOnlyTheOpenFilesAreShown() = runBlocking<Unit> {
        val gateway = RecordingGateway("Nothing to change.")

        EditPipeline(gateway, bridge(), "m").run(task("fix parseReply so empty text works"))

        val prompt = gateway.requests.single().prompt
        assertFalse(prompt.contains("=== Parser.kt ==="))
        assertFalse(prompt.contains("fun parseReply() = 1"))
    }

    @Test
    fun aFileAlreadyOpenIsNotShownTwice() = runBlocking<Unit> {
        val gateway = RecordingGateway("Nothing to change.")

        EditPipeline(gateway, bridge(openParser = true), "m", index()).run(task("fix parseReply so empty text works"))

        val prompt = gateway.requests.single().prompt
        assertEquals(1, Regex.escape("=== Parser.kt").toRegex().findAll(prompt).count())
    }

    @Test
    fun aRelatedFileThatIsTooBigIsSkippedButASmallOneIsKept() = runBlocking<Unit> {
        val big = "fun parseReply() = 0\n" + "// filler line\n".repeat(700)
        val small = "fun parseReply() = 1\n"
        val ws = index {
            put("Big.kt", big)
            put("Small.kt", small)
        }
        val bridge = bridge(extraDisk = mapOf("Big.kt" to big, "Small.kt" to small))
        val gateway = RecordingGateway("Nothing to change.")

        EditPipeline(gateway, bridge, "m", ws).run(task("fix parseReply bug"))

        val prompt = gateway.requests.single().prompt
        assertTrue(prompt.contains("=== Small.kt ==="))
        assertFalse(prompt.contains("=== Big.kt ==="))
        assertFalse(prompt.contains("// filler line"))
    }

    @Test
    fun anEditToARelatedFileIsProposedAndTheReplySaysItWasRead() = runBlocking<Unit> {
        val reply = listOf(
            "Parser.kt",
            "<<<<<<< SEARCH",
            "fun parseReply() = 1",
            "=======",
            "fun parseReply() = 2",
            ">>>>>>> REPLACE",
        ).joinToString("\n")
        val bridge = bridge()
        val gateway = RecordingGateway(reply)

        val result = EditPipeline(gateway, bridge, "m", index()).run(task("change parseReply to return 2"))

        val edit = bridge.proposed.single()
        assertEquals("Parser.kt", edit.path)
        assertEquals("fun parseReply() = 2\n", edit.newText)
        assertFalse(edit.isNew)
        assertTrue(result.success)
        assertTrue(result.output.contains("Proposed."))
        assertTrue(result.output.endsWith("Also read for context: Parser.kt."))
    }

    @Test
    fun aBrokenIndexNeverStopsAnEdit() = runBlocking<Unit> {
        val ws = index { failure = IOException("disk gone") }
        val gateway = RecordingGateway("Nothing to change.")

        val result = EditPipeline(gateway, bridge(), "m", ws).run(task("fix parseReply so empty text works"))

        assertEquals("Nothing to change.", result.output)
        assertTrue(gateway.requests.single().prompt.contains("=== Main.kt"))
    }
}
