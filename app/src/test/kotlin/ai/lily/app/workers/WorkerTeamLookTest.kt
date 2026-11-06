package ai.lily.app.workers

import ai.lily.app.core.WorkerType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lily gives the workers she creates an icon and colour, and an unknown key never reaches a worker. */
class WorkerTeamLookTest {
    private class FakeRoster(var workers: List<WorkerProfile> = emptyList()) : WorkerRoster {
        override fun list() = workers

        override fun save(workers: List<WorkerProfile>) {
            this.workers = workers
        }

        override suspend fun models(router: Int): List<String> = if (router == 0) listOf("m-a", "m-b") else emptyList()
    }

    private fun create(json: String): TeamAction.Create =
        WorkerTeam.parse("[$json]")!!.single() as TeamAction.Create

    @Test
    fun aKnownIconAndColourAreKeptEvenInCapitals() {
        val a = create("""{"action":"create","role":"TEST","icon":"Search","color":"CORAL"}""")
        assertEquals("search", a.icon)
        assertEquals("coral", a.color)
    }

    @Test
    fun anUnknownOrMissingKeyBecomesBlank() {
        val a = create("""{"action":"create","role":"TEST","icon":"rocket","color":"purple"}""")
        assertEquals("", a.icon)
        assertEquals("", a.color)
        val b = create("""{"action":"create","role":"CODING"}""")
        assertEquals("", b.icon)
        assertEquals("", b.color)
    }

    @Test
    fun everyWorkerMadeGetsTheLookAndIsMarkedAsLilys() {
        val roster = FakeRoster()
        val note = runBlocking {
            WorkerTeam.apply(listOf(create("""{"action":"create","name":"Tester","role":"TEST","count":2,"icon":"star","color":"pad"}""")), roster)
        }
        assertTrue(note, note.startsWith("Created 2"))
        assertEquals(2, roster.workers.size)
        assertTrue(roster.workers.all { it.byLily && it.iconKey == "star" && it.colorKey == "pad" })
    }

    @Test
    fun aWorkerMadeWithoutALookShowsTheRolesOwn() {
        val roster = FakeRoster()
        runBlocking { WorkerTeam.apply(listOf(create("""{"action":"create","role":"RESEARCH"}""")), roster) }
        val w = roster.workers.single()
        assertEquals("", w.iconKey)
        assertEquals("search", WorkerLook.icon(w.role, w.iconKey))
        assertEquals(WorkerType.RESEARCH, w.role)
    }
}
