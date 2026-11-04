package ai.lily.app.core

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.ModelResponse
import ai.lily.app.tools.ToolSystem
import ai.lily.app.workers.Worker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerManagerTest {

    private object NoGateway : ModelGateway {
        override suspend fun complete(request: ModelRequest): ModelResponse = error("unused")
    }

    private class Fake(type: WorkerType, private val label: String, private val fails: Boolean = false) :
        Worker(type, NoGateway, ToolSystem()) {
        override suspend fun run(task: Task, context: Context): TaskResult {
            if (fails) throw IllegalStateException("rate limited")
            return TaskResult(task.id, label, true)
        }
    }

    private fun assign(manager: WorkerManager, type: WorkerType) =
        runBlocking { manager.assign(Task("t", "d", type), Context()) }

    @Test
    fun workersOfOneRoleTakeTurns() {
        val m = WorkerManager().apply {
            register(Fake(WorkerType.TEST, "a"))
            register(Fake(WorkerType.TEST, "b"))
        }
        assertEquals(listOf("a", "b", "a", "b"), (1..4).map { assign(m, WorkerType.TEST).output })
    }

    @Test
    fun aFailingWorkerHandsTheTaskToTheNext() {
        val m = WorkerManager().apply {
            register(Fake(WorkerType.TEST, "a", fails = true))
            register(Fake(WorkerType.TEST, "b"))
        }
        val r = assign(m, WorkerType.TEST)
        assertTrue(r.success)
        assertEquals("b", r.output)
    }

    @Test
    fun whenEveryWorkerFailsTheResultIsAFailure() {
        val m = WorkerManager().apply { register(Fake(WorkerType.TEST, "a", fails = true)) }
        val r = assign(m, WorkerType.TEST)
        assertFalse(r.success)
        assertTrue(r.output.contains("rate limited"))
    }

    @Test
    fun aRoleWithNoWorkerGoesToTheGeneralOne() {
        val m = WorkerManager().apply { register(Fake(WorkerType.GENERAL, "g")) }
        assertEquals("g", assign(m, WorkerType.TEST).output)
    }
}
