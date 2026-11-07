package ai.lily.app.workers

import ai.lily.app.tools.PageAction
import ai.lily.app.tools.PageElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowActionTest {

    private val items = listOf(PageElement(0, "input[email]", "Email", ""), PageElement(1, "button", "Sign in", ""))

    @Test
    fun aClickNamesTheItemAndItsLabel() {
        assertEquals(
            FlowAction.Do(PageAction.Click(1, "Sign in")),
            FlowAction.parse("""{"action":"click","i":1}""", items),
        )
    }

    @Test
    fun typingCarriesTheText() {
        assertEquals(
            FlowAction.Do(PageAction.Type(0, "Email", "test@example.com")),
            FlowAction.parse("""{"action":"type","i":0,"text":"test@example.com"}""", items),
        )
    }

    @Test
    fun doneCarriesTheResult() {
        assertEquals(FlowAction.Done("it works"), FlowAction.parse("""{"action":"done","result":"it works"}""", items))
    }

    @Test
    fun jsonInsideProseIsFound() {
        val reply = "Sure, here you go:\n" + """{"action":"click","i":0}""" + "\nHope that helps."
        assertEquals(FlowAction.Do(PageAction.Click(0, "Email")), FlowAction.parse(reply, items))
    }

    @Test
    fun anythingThatIsNotAnActionIsNull() {
        assertNull(FlowAction.parse("no json here", items))
        assertNull(FlowAction.parse("""{"action":"navigate","url":"https://evil.test"}""", items))
        assertNull(FlowAction.parse("""{"action":"script","code":"alert(1)"}""", items))
        assertNull(FlowAction.parse("""{"action":"type","i":0}""", items))
    }

    @Test
    fun anItemThatIsNotInTheListIsNull() {
        assertNull(FlowAction.parse("""{"action":"click","i":99}""", items))
    }

    @Test
    fun longTypedTextIsCut() {
        val long = "a".repeat(500)
        val action = FlowAction.parse("""{"action":"type","i":0,"text":"$long"}""", items) as FlowAction.Do
        assertEquals(200, (action.action as PageAction.Type).text.length)
        assertTrue(action.action is PageAction.Type)
    }
}
