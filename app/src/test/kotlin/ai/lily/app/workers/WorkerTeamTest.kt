package ai.lily.app.workers

import ai.lily.app.core.WorkerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model ids, update and describe: what Lily may name, what she may change, and what the person is asked to approve. */
class WorkerTeamTest {
    private class FakeRoster(var workers: List<WorkerProfile> = emptyList()) : WorkerRoster {
        override fun list() = workers

        override fun save(workers: List<WorkerProfile>) {
            this.workers = workers
        }

        override suspend fun models(router: Int): List<String> = if (router == 0) listOf("m-a", "m-b") else listOf("n-a")
    }

    private fun profile(name: String, byLily: Boolean, router: Int = 0, model: String = "m-a") =
        WorkerProfile(id = name, name = name, role = WorkerType.TEST, router = router, model = model, instructions = "old", byLily = byLily)

    private fun actions(json: String) = WorkerTeam.parse("[$json]")!!

    @Test
    fun anExactModelIdIsUsedWhenARouterOffersIt() {
        val roster = FakeRoster()
        runBlocking { WorkerTeam.apply(actions("""{"action":"create","role":"TEST","model":"N-A"}"""), roster) }
        val w = roster.workers.single()
        assertEquals("n-a", w.model)
        assertEquals(1, w.router)
    }

    @Test
    fun anUnknownModelFallsBackToTheFilterAndSaysSo() {
        val roster = FakeRoster()
        val note = runBlocking { WorkerTeam.apply(actions("""{"action":"create","role":"TEST","model":"nope","router":1}"""), roster) }
        assertEquals("m-a", roster.workers.single().model)
        assertTrue(note, note.contains("not offered"))
    }

    @Test
    fun updateChangesOnlyTheFieldsGivenOnWorkersLilyMade() {
        val roster = FakeRoster(listOf(profile("Tester", byLily = true)))
        runBlocking { WorkerTeam.apply(actions("""{"action":"update","name":"tester","instructions":"new"}"""), roster) }
        val w = roster.workers.single()
        assertEquals("new", w.instructions)
        assertEquals("m-a", w.model)
    }

    @Test
    fun updateMovesAWorkerToTheRouterThatOffersTheModel() {
        val roster = FakeRoster(listOf(profile("Tester", byLily = true)))
        runBlocking { WorkerTeam.apply(actions("""{"action":"update","name":"Tester","model":"n-a"}"""), roster) }
        val w = roster.workers.single()
        assertEquals(1, w.router)
        assertEquals("n-a", w.model)
    }

    @Test
    fun updateAndDismissLeaveWorkersThePersonMade() {
        val roster = FakeRoster(listOf(profile("Mine", byLily = false)))
        val note = runBlocking {
            WorkerTeam.apply(actions("""{"action":"update","name":"Mine","instructions":"x"},{"action":"dismiss","name":"*"}"""), roster)
        }
        assertEquals(listOf(profile("Mine", byLily = false)), roster.workers)
        assertTrue(note, note.contains("made by you"))
    }

    @Test
    fun anUpdateThatChangesNothingIsDropped() {
        assertTrue(actions("""{"action":"update","name":"Tester"}""").isEmpty())
    }

    @Test
    fun describeIsOneLinePerChangeWithoutControlCharacters() {
        val lines = WorkerTeam.describe(actions("""{"action":"create","name":"A\nB","role":"TEST","count":2},{"action":"dismiss","name":"*"}"""))
        assertEquals(listOf("Create", "Dismiss"), lines.map { it.first })
        assertTrue(lines.none { it.second.contains('\n') })
        assertEquals("every worker Lily made", lines[1].second)
    }

    @Test
    fun modelLinesCutsLongListsAndNamesEmptyOnes() {
        val text = WorkerTeam.modelLines(mapOf(1 to listOf("a", "b", "c"), 2 to emptyList()), perRouter = 2)
        assertEquals("Router 1: a, b ... and 1 more (modelFilter finds them)", text)
        assertTrue(WorkerTeam.modelLines(emptyMap()).contains("no models are loaded"))
    }
}
