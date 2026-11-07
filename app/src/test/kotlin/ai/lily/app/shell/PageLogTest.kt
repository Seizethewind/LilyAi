package ai.lily.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageLogTest {

    @Test
    fun onlyTheNewestEntriesAreKept() {
        val log = PageLog(cap = 3)
        for (t in listOf("a", "b", "c", "d", "e")) log.add(PageLog.Kind.Console, t)
        assertEquals(listOf("c", "d", "e"), log.entries().map { it.text })
    }

    @Test
    fun aLongMessageIsCut() {
        val log = PageLog()
        log.add(PageLog.Kind.Error, "x".repeat(PageLog.MAX_TEXT + 100))
        assertEquals(PageLog.MAX_TEXT, log.entries().single().text.length)
    }

    @Test
    fun kindsAreKept() {
        val log = PageLog()
        log.add(PageLog.Kind.Http, "404 https://example.com/app.js")
        assertEquals(PageLog.Kind.Http, log.entries().single().kind)
    }

    @Test
    fun loadTimeIsKnownOnlyOnceThePageFinished() {
        val log = PageLog()
        assertNull(log.loadMillis())
        log.started(1_000)
        assertNull(log.loadMillis())
        log.finished(1_800)
        assertEquals(800L, log.loadMillis())
    }

    @Test
    fun aNewPageClearsTheLogAndTheClock() {
        val log = PageLog()
        log.started(1_000)
        log.add(PageLog.Kind.Console, "old")
        log.finished(1_500)
        log.started(5_000)
        assertEquals(emptyList<PageLog.Entry>(), log.entries())
        assertNull(log.loadMillis())
    }
}
