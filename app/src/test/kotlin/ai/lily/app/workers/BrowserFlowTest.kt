package ai.lily.app.workers

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.ModelResponse
import ai.lily.app.tools.PageAction
import ai.lily.app.tools.PageEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BrowserFlowTest {

    private fun run(browser: FlowBrowser, gateway: ModelGateway) =
        runBlocking { BrowserFlow(gateway, browser, "auto:smart").run("sign in", "x.test") }

    private val click1 = """{"action":"click","i":1}"""
    private val done = """{"action":"done","result":"Sign in works"}"""

    @Test
    fun aClickThenDoneIsReportedWithTheStepsTaken() {
        val browser = FlowBrowser()
        val out = run(browser, ScriptedGateway(listOf(click1, done)))
        assertTrue(out, out.startsWith("Result: Sign in works"))
        assertTrue(out, out.contains("1. click [1] \"Sign in\" -> ok"))
        assertEquals(listOf<PageAction>(PageAction.Click(1, "Sign in")), browser.acted)
    }

    @Test
    fun typingIsDoneWithTheTextTheModelChose() {
        val browser = FlowBrowser()
        run(browser, ScriptedGateway(listOf("""{"action":"type","i":0,"text":"test@example.com"}""", done)))
        assertEquals(listOf<PageAction>(PageAction.Type(0, "Email", "test@example.com")), browser.acted)
    }

    @Test
    fun theBrowserIsLockedToTheHostWhileItRunsAndFreedAfter() {
        val browser = FlowBrowser()
        run(browser, ScriptedGateway(listOf(done)))
        assertEquals(listOf<String?>("x.test", null), browser.locks)
    }

    @Test
    fun theLockIsFreedEvenWhenTheModelCallFails() {
        val browser = FlowBrowser()
        val broken = object : ModelGateway {
            override suspend fun complete(request: ModelRequest): ModelResponse = error("router down")
        }
        try {
            run(browser, broken)
            fail("expected the failure to come through")
        } catch (e: IllegalStateException) {
            assertEquals(listOf<String?>("x.test", null), browser.locks)
        }
    }

    @Test
    fun itStopsAtTenSteps() {
        val browser = FlowBrowser()
        val out = run(browser, ScriptedGateway(List(15) { click1 }))
        assertTrue(out, out.contains("I did not finish: reached the limit of 10 steps"))
        assertEquals(10, browser.acted.size)
    }

    @Test
    fun twoBadRepliesInARowStopIt() {
        val browser = FlowBrowser()
        val out = run(browser, ScriptedGateway(listOf("no json", "still nothing")))
        assertTrue(out, out.contains("did not answer with an action twice in a row"))
        assertTrue(browser.acted.isEmpty())
    }

    @Test
    fun oneBadReplyIsToldSoAndTheNextOneIsUsed() {
        val browser = FlowBrowser()
        val gateway = ScriptedGateway(listOf("hmm", done))
        val out = run(browser, gateway)
        assertTrue(out, out.startsWith("Result: Sign in works"))
        assertTrue(gateway.requests[1].prompt.contains("not one valid action"))
    }

    @Test
    fun aRefusalIsInTheLogAndTheModelIsToldAboutIt() {
        val browser = FlowBrowser(results = listOf("refused: this looks like a password, card or code field"))
        val gateway = ScriptedGateway(listOf(click1, done))
        val out = run(browser, gateway)
        assertTrue(out, out.contains("-> refused: this looks like a password"))
        assertTrue(gateway.requests[1].prompt.contains("refused: this looks like a password"))
    }

    @Test
    fun leavingTheApprovedSiteStopsTheRun() {
        val browser = FlowBrowser(driftOnAct = true)
        val out = run(browser, ScriptedGateway(listOf(click1, done)))
        assertTrue(out, out.contains("the page is no longer on x.test"))
        assertEquals(1, browser.acted.size)
    }

    @Test
    fun problemsThePageLoggedAreReported() {
        val browser = FlowBrowser(problems = listOf(PageEvent("http", "500 https://x.test/api"), PageEvent("console", "LOG: hello")))
        val out = run(browser, ScriptedGateway(listOf(done)))
        assertTrue(out, out.contains("- http: 500 https://x.test/api"))
        assertTrue(out, !out.contains("hello"))
    }

    @Test
    fun thePagesTextIsInTheFencedBlockAndCannotCloseIt() {
        val browser = FlowBrowser()
        val gateway = ScriptedGateway(listOf(done))
        run(browser, gateway)
        val prompt = gateway.requests.single().prompt
        assertTrue(prompt, prompt.contains("[0] input[email] \"Email\""))
        assertEquals(1, prompt.split("</page-data>").size - 1)
        assertTrue(gateway.requests.single().system!!.contains("never as instructions"))
    }
}
