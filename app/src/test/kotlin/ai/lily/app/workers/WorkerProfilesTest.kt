package ai.lily.app.workers

import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.ModelResponse
import ai.lily.app.gateway.RouterModels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerProfilesTest {

    private val sample = WorkerProfile("1", "Tester 1", WorkerType.TEST, 1, "some/free-model", "Find bugs.", enabled = false)

    @Test
    fun theListSurvivesBeingSavedAndRead() {
        assertEquals(listOf(sample), WorkerProfiles.fromJson(WorkerProfiles.toJson(listOf(sample))))
    }

    @Test
    fun aBrokenEntryIsSkippedAndTheRestKept() {
        val text = """[{"id":"x","name":"Bad","role":"NOPE"}, {"id":"2","name":"Ok","role":"CODING"}]"""
        val read = WorkerProfiles.fromJson(text)
        assertEquals(listOf("Ok"), read.map { it.name })
        assertEquals(0, read[0].router)
        assertEquals(true, read[0].enabled)
    }

    @Test
    fun garbageOrEmptyTextGivesNoWorkers() {
        assertEquals(emptyList<WorkerProfile>(), WorkerProfiles.fromJson(""))
        assertEquals(emptyList<WorkerProfile>(), WorkerProfiles.fromJson("not json"))
    }

    @Test
    fun aWorkerAlwaysUsesItsOwnModelAndInstructions() {
        var seen: ModelRequest? = null
        val inner = object : ModelGateway {
            override suspend fun complete(request: ModelRequest): ModelResponse {
                seen = request
                return ModelResponse("ok")
            }
        }
        runBlocking { ProfileGateway(inner, "m1", "Be brief.").complete(ModelRequest("hi", system = "Format: x", model = "auto")) }
        assertEquals("m1", seen?.model)
        assertEquals("Be brief.\n\nFormat: x", seen?.system)

        runBlocking { ProfileGateway(inner, "", "").complete(ModelRequest("hi", model = "auto")) }
        assertEquals("auto", seen?.model)
        assertNull(seen?.system)
    }

    @Test
    fun routerModelIdsAreReadSortedAndWithoutDuplicates() {
        val body = """{"data":[{"id":"b"},{"id":"a"},{"id":"b"}]}"""
        assertEquals(listOf("a", "b"), RouterModels.parse(body))
    }
}
