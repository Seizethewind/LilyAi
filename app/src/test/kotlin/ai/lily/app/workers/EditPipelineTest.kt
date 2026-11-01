package ai.lily.app.workers

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

class EditPipelineTest {

    private class FakeGateway(private val replies: MutableList<String>) : ModelGateway {
        val prompts = mutableListOf<String>()

        override suspend fun complete(request: ModelRequest): ModelResponse {
            prompts += request.prompt
            return ModelResponse(replies.removeAt(0))
        }
    }

    private class FakeEditor(private val files: Map<String, String>, private val active: String?) : EditorBridge {
        val proposed = mutableListOf<ProposedEdit>()

        override suspend fun openFiles(): List<OpenFile> =
            files.filterKeys { it == active }.map { OpenFile(it.key, it.value, active = true, dirty = false) }

        override suspend fun listFiles(): List<String> = files.keys.sorted()

        override suspend fun readFile(path: String): String? = files[path]

        override suspend fun propose(edits: List<ProposedEdit>): String {
            proposed += edits
            return "proposed ${edits.size}"
        }
    }

    private val task = Task("t1", "change a", WorkerType.CODING)

    private fun run(gateway: FakeGateway, editor: FakeEditor) =
        runBlocking { EditPipeline(gateway, editor, "auto").run(task) }

    private fun block(path: String, search: String, replace: String) =
        "$path\n<<<<<<< SEARCH\n$search\n=======\n$replace\n>>>>>>> REPLACE\n"

    @Test
    fun anEditIsProposedAgainstTheOpenFile() {
        val editor = FakeEditor(mapOf("a.kt" to "fun a() = 1\nfun b() = 2\n"), active = "a.kt")
        val gateway = FakeGateway(mutableListOf("Changed a.\n\n" + block("a.kt", "fun a() = 1", "fun a() = 3")))
        val result = run(gateway, editor)
        assertTrue(result.success)
        assertEquals(1, editor.proposed.size)
        assertEquals("fun a() = 3\nfun b() = 2\n", editor.proposed[0].newText)
        assertFalse(editor.proposed[0].isNew)
        assertTrue(result.output.contains("Changed a."))
        assertTrue(result.output.contains("proposed 1"))
        assertTrue(gateway.prompts[0].contains("fun a() = 1"))
    }

    @Test
    fun aPlainAnswerIsPassedOnWithoutProposals() {
        val editor = FakeEditor(mapOf("a.kt" to "x"), active = "a.kt")
        val result = run(FakeGateway(mutableListOf("It prints x.")), editor)
        assertTrue(result.success)
        assertEquals("It prints x.", result.output)
        assertTrue(editor.proposed.isEmpty())
    }

    @Test
    fun aBlockThatDoesNotMatchIsRetriedOnce() {
        val editor = FakeEditor(mapOf("a.kt" to "fun a() = 1\n"), active = "a.kt")
        val gateway = FakeGateway(
            mutableListOf(
                block("a.kt", "fun zzz() = 1", "fun a() = 2"),
                block("a.kt", "fun a() = 1", "fun a() = 2"),
            ),
        )
        val result = run(gateway, editor)
        assertTrue(result.success)
        assertEquals(2, gateway.prompts.size)
        assertEquals("fun a() = 2\n", editor.proposed.single().newText)
    }

    @Test
    fun twoBadAttemptsMeanNoProposalAndAFailure() {
        val editor = FakeEditor(mapOf("a.kt" to "fun a() = 1\n"), active = "a.kt")
        val gateway = FakeGateway(
            mutableListOf(
                block("a.kt", "nope", "x"),
                block("a.kt", "still nope", "x"),
            ),
        )
        val result = run(gateway, editor)
        assertFalse(result.success)
        assertTrue(editor.proposed.isEmpty())
        assertTrue(result.output.contains("Could not apply"))
    }

    @Test
    fun aNewFileIsProposedAsNew() {
        val editor = FakeEditor(mapOf("a.kt" to "x"), active = "a.kt")
        val gateway = FakeGateway(mutableListOf("new.py\n<<<<<<< SEARCH\n=======\nprint(1)\n>>>>>>> REPLACE\n"))
        val result = run(gateway, editor)
        assertTrue(result.success)
        val p = editor.proposed.single()
        assertEquals("new.py", p.path)
        assertEquals("print(1)", p.newText)
        assertTrue(p.isNew)
    }

    @Test
    fun aPathOutsideTheWorkspaceIsNeverProposed() {
        val editor = FakeEditor(mapOf("a.kt" to "x"), active = "a.kt")
        val gateway = FakeGateway(
            mutableListOf(
                block("../evil.kt", "x", "y"),
                "Sorry.",
            ),
        )
        val result = run(gateway, editor)
        assertFalse(result.success)
        assertTrue(editor.proposed.isEmpty())
    }

    @Test
    fun aFileTheRequestNamesIsShownEvenWhenItIsNotOpen() {
        val editor = FakeEditor(mapOf("a.kt" to "x", "util/b.kt" to "secret b text"), active = "a.kt")
        val gateway = FakeGateway(mutableListOf("ok"))
        val named = Task("t2", "look at util/b.kt", WorkerType.CODING)
        runBlocking { EditPipeline(gateway, editor, "auto").run(named) }
        assertTrue(gateway.prompts[0].contains("secret b text"))
    }
}
