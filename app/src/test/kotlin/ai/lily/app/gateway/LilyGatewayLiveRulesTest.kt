package ai.lily.app.gateway

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LilyGatewayLiveRulesTest {
    private class Fake : ModelGateway {
        var seen: ModelRequest? = null
        override suspend fun complete(request: ModelRequest): ModelResponse {
            seen = request
            return ModelResponse("ok")
        }
    }

    @Test
    fun rulesAreAskedForOnEveryCall() {
        val fake = Fake()
        var current = "first"
        val gateway = LilyGateway(fake, { current })
        runBlocking { gateway.complete(ModelRequest("hi")) }
        assertEquals(LilyGateway.HEADING + "first", fake.seen?.system)
        current = "second"
        runBlocking { gateway.complete(ModelRequest("hi")) }
        assertEquals(LilyGateway.HEADING + "second", fake.seen?.system)
    }

    @Test
    fun emptyLiveRulesAddNothing() {
        val fake = Fake()
        runBlocking { LilyGateway(fake, { "  " }).complete(ModelRequest("hi")) }
        assertNull(fake.seen?.system)
    }
}
