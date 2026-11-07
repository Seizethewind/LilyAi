package ai.lily.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageActionsTest {

    /** What evaluateJavascript returns for a script that returns the JSON text [json]. */
    private fun jsReturning(json: String): String =
        "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @Test
    fun theListScriptCarriesItsHelpers() {
        val s = PageActions.elementsScript()
        assertTrue(s.contains("function list()"))
        assertTrue(s.contains("JSON.stringify"))
    }

    @Test
    fun valuesAreWrittenAsJsonSoTheyCannotBreakOut() {
        val s = PageActions.actionScript(PageAction.Type(2, "say \"hi\"", "</script>');alert(1);//"))
        assertTrue(s, s.contains("say \\\"hi\\\""))
        assertFalse(s, s.contains("say \"hi\""))
        assertTrue(s, s.contains("\"kind\":\"type\""))
    }

    @Test
    fun aClickScriptNamesTheItem() {
        val s = PageActions.actionScript(PageAction.Click(3, "Sign in"))
        assertTrue(s, s.contains("\"kind\":\"click\""))
        assertTrue(s, s.contains("\"i\":3"))
    }

    @Test
    fun anAnswerIsReducedToOkRefusedOrError() {
        assertEquals("ok", PageActions.result(jsReturning("""{"ok":true}""")))
        assertEquals(
            "refused: this looks like a password, card or code field",
            PageActions.result(jsReturning("""{"ok":false,"kind":"refused","why":"this looks like a password, card or code field"}""")),
        )
        assertEquals("error: the page changed", PageActions.result(jsReturning("""{"ok":false,"kind":"error","why":"the page changed"}""")))
    }

    @Test
    fun aPageCannotMakeItsOwnAnswerLookLikeSomethingElse() {
        // An unknown kind is an error, and the reason is flattened and cut.
        val r = PageActions.result(jsReturning("""{"ok":false,"kind":"ok","why":"line one\nline two"}"""))
        assertEquals("error: line one line two", r)
    }

    @Test
    fun noAnswerIsAnError() {
        assertTrue(PageActions.result(null).startsWith("error"))
        assertTrue(PageActions.result("null").startsWith("error"))
        assertTrue(PageActions.result(jsReturning("not json")).startsWith("error"))
    }
}
