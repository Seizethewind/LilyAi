package ai.lily.app.gateway

import ai.lily.app.core.Context
import ai.lily.app.core.LlmPlanner
import ai.lily.app.core.WorkerType
import ai.lily.app.workers.ProfileGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LilyGatewayTest {
    private class Fake(var reply: String = "ok") : ModelGateway {
        var seen: ModelRequest? = null
        override suspend fun complete(request: ModelRequest): ModelResponse {
            seen = request
            return ModelResponse(reply)
        }
    }

    @Test
    fun `blank rules and model leave the request as it was`() {
        val fake = Fake()
        runBlocking { LilyGateway(fake, "  ").complete(ModelRequest("hi", system = "Format: x", model = "auto")) }
        assertEquals(ModelRequest("hi", system = "Format: x", model = "auto"), fake.seen)
        runBlocking { LilyGateway(fake, "").complete(ModelRequest("hi")) }
        assertNull(fake.seen?.system)
        assertNull(fake.seen?.model)
    }

    @Test
    fun `the rules come after the callers own system text, or alone when there is none`() {
        val fake = Fake()
        runBlocking { LilyGateway(fake, "Be kind.").complete(ModelRequest("hi", system = "Format: x")) }
        assertEquals("Format: x\n\n" + LilyGateway.HEADING + "Be kind.", fake.seen?.system)
        runBlocking { LilyGateway(fake, "Be kind.").complete(ModelRequest("hi")) }
        assertEquals(LilyGateway.HEADING + "Be kind.", fake.seen?.system)
    }

    @Test
    fun `a model replaces the requested one only when it is not blank`() {
        val fake = Fake()
        runBlocking { LilyGateway(fake, "", "mine").complete(ModelRequest("hi", model = "auto:smart")) }
        assertEquals("mine", fake.seen?.model)
        runBlocking { LilyGateway(fake, "", "").complete(ModelRequest("hi", model = "auto:smart")) }
        assertEquals("auto:smart", fake.seen?.model)
    }

    @Test
    fun `a worker sees its own rules, then the request rules, then Lilys, and keeps its own model`() {
        val fake = Fake()
        val worker = ProfileGateway(LilyGateway(fake, "Lily rule", ""), "worker-model", "Worker rule")
        runBlocking { worker.complete(ModelRequest("hi", system = "Format: x", model = "auto")) }
        val system = fake.seen?.system.orEmpty()
        assertTrue(system.indexOf("Worker rule") < system.indexOf("Format: x"))
        assertTrue(system.indexOf("Format: x") < system.indexOf("Lily rule"))
        assertEquals("worker-model", fake.seen?.model)
    }

    @Test
    fun `the planner keeps its JSON rule first, still parses, and plans with Lilys model`() {
        val fake = Fake("""[{"description":"do it","worker":"CODING"}]""")
        val tasks = runBlocking { LlmPlanner(LilyGateway(fake, "Answer in Spanish.", "my-model")).plan("x", Context()) }
        val system = fake.seen?.system.orEmpty()
        assertTrue(system.indexOf("ONLY a JSON array") in 0 until system.indexOf("Answer in Spanish."))
        assertEquals("my-model", fake.seen?.model)
        assertEquals(listOf(WorkerType.CODING), tasks.map { it.worker })
    }
}
