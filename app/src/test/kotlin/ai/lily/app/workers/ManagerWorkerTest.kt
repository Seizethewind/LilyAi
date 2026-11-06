package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.ModelResponse
import ai.lily.app.plugins.ConfirmRequest
import ai.lily.app.plugins.ConfirmationGate
import ai.lily.app.tools.ToolSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The TEAM worker asks the person before it changes the team, and changes nothing when the answer is no. */
class ManagerWorkerTest {
    private class FakeRoster(var workers: List<WorkerProfile> = emptyList()) : WorkerRoster {
        var saves = 0

        override fun list() = workers

        override fun save(workers: List<WorkerProfile>) {
            this.workers = workers
            saves++
        }

        override suspend fun models(router: Int): List<String> = if (router == 0) listOf("m-a") else emptyList()
    }

    private class FakeGateway(private val reply: String) : ModelGateway {
        var system: String? = null

        override suspend fun complete(request: ModelRequest): ModelResponse {
            system = request.system
            return ModelResponse(reply)
        }
    }

    private class FakeGate(private val answer: Boolean) : ConfirmationGate {
        val asked = mutableListOf<ConfirmRequest>()

        override suspend fun confirm(request: ConfirmRequest): Boolean {
            asked += request
            return answer
        }
    }

    private val makeTwo = """[{"action":"create","name":"Tester","role":"TEST","count":2}]"""

    private fun run(gateway: ModelGateway, roster: WorkerRoster, gate: ConfirmationGate?): TaskResult = runBlocking {
        ManagerWorker(gateway, ToolSystem(), roster, gate).run(Task("t", "make testers", WorkerType.TEAM), Context())
    }

    private fun profile(name: String, byLily: Boolean) =
        WorkerProfile(id = name, name = name, role = WorkerType.TEST, router = 0, model = "m-a", instructions = "", byLily = byLily)

    @Test
    fun approvedChangesAreMadeAfterExactlyOneQuestion() {
        val roster = FakeRoster()
        val gate = FakeGate(true)
        val result = run(FakeGateway(makeTwo), roster, gate)
        assertTrue(result.output, result.success && result.output.startsWith("Created 2"))
        assertEquals(2, roster.workers.size)
        assertEquals(1, gate.asked.size)
        assertEquals("lily.team", gate.asked.single().pluginId)
        assertEquals("Create", gate.asked.single().details.single().first)
    }

    @Test
    fun aRefusalLeavesTheTeamAsItWas() {
        val roster = FakeRoster(listOf(profile("Mine", byLily = false)))
        val result = run(FakeGateway(makeTwo), roster, FakeGate(false))
        assertTrue(result.output, result.output.contains("did not approve"))
        assertEquals(0, roster.saves)
        assertEquals(1, roster.workers.size)
    }

    @Test
    fun withoutAGateTheChangesAreMadeAtOnce() {
        val roster = FakeRoster()
        run(FakeGateway(makeTwo), roster, gate = null)
        assertEquals(2, roster.workers.size)
    }

    @Test
    fun aReplyThatIsNotAListChangesNothingAndAsksNothing() {
        val gate = FakeGate(true)
        val roster = FakeRoster()
        val result = run(FakeGateway("Sorry, no."), roster, gate)
        assertFalse(result.success)
        assertTrue(gate.asked.isEmpty())
        assertEquals(0, roster.saves)
    }

    @Test
    fun anEmptyListAsksNothing() {
        val gate = FakeGate(true)
        val result = run(FakeGateway("[]"), FakeRoster(), gate)
        assertTrue(result.success)
        assertEquals("Nothing to change.", result.output)
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun thePromptShowsModelIdsAndWhoMadeEachWorker() {
        val gateway = FakeGateway("[]")
        run(gateway, FakeRoster(listOf(profile("Tester", byLily = true), profile("Mine", byLily = false))), gate = null)
        val system = gateway.system.orEmpty()
        assertTrue(system, system.contains("Router 1: m-a"))
        assertTrue(system, system.contains("- Tester (test, made by you)"))
        assertTrue(system, system.contains("- Mine (test, made by the person)"))
    }
}
