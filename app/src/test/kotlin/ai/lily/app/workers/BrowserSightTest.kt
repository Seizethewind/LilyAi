package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelImage
import ai.lily.app.tools.ToolSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSightTest {

    @Test
    fun aReplyThatStartsWithTheCodeIsAccepted() {
        assertEquals("Cramped header.", BrowserSight.readSeen("LILY-1234\nCramped header.", "LILY-1234"))
        assertEquals("Cramped header.", BrowserSight.readSeen("Code: lily-1234\nCramped header.", "LILY-1234"))
        assertEquals("Cramped header.", BrowserSight.readSeen("LILY-1234: Cramped header.", "LILY-1234"))
    }

    @Test
    fun aReplyWithoutTheCodeIsNotAccepted() {
        assertNull(BrowserSight.readSeen("NONE\nLooks fine", "LILY-1234"))
        assertNull(BrowserSight.readSeen("LILY-9999\nLooks fine", "LILY-1234"))
        assertNull(BrowserSight.readSeen("", "LILY-1234"))
        // The code only later in the reply is a guess, not a reading.
        assertNull(BrowserSight.readSeen("Looks fine\nLILY-1234", "LILY-1234"))
    }

    private val picture = ModelImage("image/jpeg", "AAA")

    private fun review(browser: FlowBrowser, gateway: VisionGateway) = runBlocking {
        BrowserTestWorker(gateway, ToolSystem(), browser, RecordingGate(true)) { "LILY-1234" }
            .run(Task("t", "test https://x.test/ for problems", WorkerType.TEST), Context())
    }

    @Test
    fun aModelThatReadTheCodeGivesTheVisualReview() {
        val browser = FlowBrowser(shot = picture)
        val gateway = VisionGateway(onImage = { "LILY-1234\nThe header is cramped." }, onText = { "code review" })
        val result = review(browser, gateway)
        assertEquals("The header is cramped.", result.output)
        assertEquals(1, gateway.requests.size)
        assertEquals(listOf(picture), gateway.requests.single().images)
        assertTrue(gateway.requests.single().system!!.contains("top-left corner"))
        assertEquals(listOf("LILY-1234"), browser.codes)
    }

    @Test
    fun aModelThatIgnoresThePictureIsNotBelieved() {
        val browser = FlowBrowser(shot = picture)
        val gateway = VisionGateway(onImage = { "NONE\nLooks great and modern." }, onText = { "code review" })
        val result = review(browser, gateway)
        assertTrue(result.output, result.output.startsWith("code review"))
        assertTrue(result.output, result.output.contains("could not read a screenshot"))
        assertFalse(result.output.contains("modern"))
        assertEquals(2, gateway.requests.size)
        assertTrue(gateway.requests[1].images.isEmpty())
    }

    @Test
    fun aModelThatInventsADifferentCodeIsNotBelieved() {
        val browser = FlowBrowser(shot = picture)
        val gateway = VisionGateway(onImage = { "LILY-9999\nLooks great." }, onText = { "code review" })
        assertTrue(review(browser, gateway).output.contains("could not read a screenshot"))
    }

    @Test
    fun aRouterThatRefusesPicturesFallsBackToTheFacts() {
        val browser = FlowBrowser(shot = picture)
        val gateway = VisionGateway(onImage = { error("Router 400: images not supported") }, onText = { "code review" })
        val result = review(browser, gateway)
        assertTrue(result.output, result.output.startsWith("code review"))
        assertTrue(result.output.contains("could not read a screenshot"))
    }

    @Test
    fun withoutAScreenshotThereIsOneTextCallAndNoNote() {
        val browser = FlowBrowser(shot = null)
        val gateway = VisionGateway(onImage = { error("no picture was made") }, onText = { "code review" })
        val result = review(browser, gateway)
        assertEquals("code review", result.output)
        assertEquals(1, gateway.requests.size)
    }
}
