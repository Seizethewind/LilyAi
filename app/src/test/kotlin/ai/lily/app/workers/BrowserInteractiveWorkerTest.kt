package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.WorkerType
import ai.lily.app.tools.ToolSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserInteractiveWorkerTest {

    @Test
    fun wordsThatAskForActionsAreNoticed() {
        assertTrue(BrowserIntent.wantsActions("click through the signup form on https://x.test"))
        assertTrue(BrowserIntent.wantsActions("Fill in the contact form"))
        assertTrue(BrowserIntent.wantsActions("walk through the checkout"))
    }

    @Test
    fun ordinaryChecksAreNotTakenForActions() {
        assertFalse(BrowserIntent.wantsActions("test https://x.test for problems"))
        assertFalse(BrowserIntent.wantsActions("check tap targets on https://x.test"))
        assertFalse(BrowserIntent.wantsActions("review the clickable areas"))
    }

    private val done = """{"action":"done","result":"works"}"""

    private fun go(text: String, browser: FlowBrowser, gateway: ScriptedGateway, gate: RecordingGate) =
        runBlocking { BrowserTestWorker(gateway, ToolSystem(), browser, gate).run(Task("t", text, WorkerType.TEST), Context()) }

    @Test
    fun anApprovedFlowOpensThePageThenRunsLockedToItsHost() {
        val browser = FlowBrowser(url = "")
        val gate = RecordingGate(true)
        val result = go("click through the signup form at https://x.test/signup", browser, ScriptedGateway(listOf(done)), gate)
        assertTrue(result.output, result.output.startsWith("Result: works"))
        assertEquals(listOf("show", "open https://x.test/signup"), browser.calls)
        assertEquals(listOf<String?>("x.test", null), browser.locks)

        val asked = gate.asked.single()
        assertTrue(asked.changes)
        assertTrue(asked.isHttp)
        assertEquals("x.test", asked.targetPackage)
        val details = asked.details.toMap()
        assertTrue(details["Limits"]!!.contains("At most 10 steps"))
        assertEquals("none found", details["Saved logins"])
        assertTrue(details["Goal"]!!.contains("signup form"))
    }

    @Test
    fun aDeniedFlowDoesNothingAtAll() {
        val browser = FlowBrowser(url = "")
        val gateway = ScriptedGateway(emptyList())
        val result = go("click through the form at https://x.test/", browser, gateway, RecordingGate(false))
        assertTrue(result.output.startsWith("denied"))
        assertTrue(browser.calls.isEmpty())
        assertTrue(browser.acted.isEmpty())
        assertTrue(browser.locks.isEmpty())
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun aSiteWithSavedLoginsIsFlaggedInTheQuestion() {
        val gate = RecordingGate(false)
        go("click through the form at https://x.test/", FlowBrowser(url = "", cookies = true), ScriptedGateway(emptyList()), gate)
        val saved = gate.asked.single().details.toMap()["Saved logins"]!!
        assertTrue(saved, saved.startsWith("YES"))
    }

    @Test
    fun thePageAlreadyOpenIsStillAskedAboutBeforeItIsUsed() {
        val browser = FlowBrowser(url = "https://x.test/")
        val gate = RecordingGate(true)
        go("click the buttons on the current website", browser, ScriptedGateway(listOf(done)), gate)
        assertEquals(1, gate.asked.size)
        assertEquals(listOf("show"), browser.calls)
    }

    @Test
    fun aPageThatIsNotWebAddressIsNotUsed() {
        val gate = RecordingGate(true)
        val browser = FlowBrowser(url = "about:blank")
        val result = go("click the buttons on the current website", browser, ScriptedGateway(emptyList()), gate)
        assertTrue(result.output, result.output.contains("cannot tell which site"))
        assertTrue(gate.asked.isEmpty())
        assertTrue(browser.acted.isEmpty())
    }
}
