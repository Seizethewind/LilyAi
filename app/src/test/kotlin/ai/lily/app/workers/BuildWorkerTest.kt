package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.ModelResponse
import ai.lily.app.tools.Tool
import ai.lily.app.tools.ToolSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildWorkerTest {

    private class FakeGateway(private val reply: String) : ModelGateway {
        override suspend fun complete(request: ModelRequest) = ModelResponse(reply)
    }

    private class FakeTerminal(private val out: String) : Tool {
        override val name = "terminal"
        var got: Map<String, String>? = null
        override suspend fun execute(args: Map<String, String>): String {
            got = args
            return out
        }
    }

    private val task = Task("t", "run echo hi in Termux", WorkerType.BUILD)

    private fun run(reply: String, terminal: FakeTerminal?) = runBlocking {
        val tools = ToolSystem().apply { terminal?.let { register(it) } }
        BuildWorker(FakeGateway(reply), tools).run(task, Context())
    }

    @Test
    fun aShellTaskIsRunThroughTheTerminalTool() {
        val t = FakeTerminal("exit=0\nhi")
        val r = run("```bash\necho hi\n```".replace("\n", " ").replace("``` ", "```"), t)
        assertEquals(mapOf("command" to "echo hi"), t.got)
        assertTrue(r.success)
        assertTrue(r.output.contains("exit=0"))
    }

    @Test
    fun aDeniedCommandIsAFailure() {
        val r = run("ls", FakeTerminal("denied: the person did not approve this, so nothing was run"))
        assertFalse(r.success)
    }

    @Test
    fun withoutTheToolOrWithNoneTheModelAnswers() {
        val none = FakeTerminal("unused")
        assertEquals("NONE", run("NONE", none).output)
        assertEquals(null, none.got)
        assertEquals("ls", run("ls", null).output)
    }
}
