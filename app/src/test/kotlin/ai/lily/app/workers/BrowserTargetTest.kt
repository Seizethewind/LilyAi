package ai.lily.app.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserTargetTest {

    @Test
    fun anAddressInTheTaskIsTheTarget() {
        assertEquals(BrowserTarget.Url("https://example.com/login"), BrowserTarget.of("test https://example.com/login please"))
    }

    @Test
    fun punctuationAfterTheAddressIsNotPartOfIt() {
        assertEquals(BrowserTarget.Url("https://example.com/a"), BrowserTarget.of("check https://example.com/a."))
        assertEquals(BrowserTarget.Url("https://x.test/p"), BrowserTarget.of("check (https://x.test/p)"))
    }

    @Test
    fun thePageAlreadyOpenCanBeNamedInWords() {
        assertEquals(BrowserTarget.Current, BrowserTarget.of("check the current website for problems"))
        assertEquals(BrowserTarget.Current, BrowserTarget.of("audit this site in the browser"))
    }

    @Test
    fun anAddressWinsOverTheWords() {
        assertEquals(BrowserTarget.Url("https://a.test"), BrowserTarget.of("open site https://a.test now"))
    }

    @Test
    fun ordinaryTesterTasksAreNotAboutAPage() {
        assertNull(BrowserTarget.of("write test cases for the login form"))
        assertNull(BrowserTarget.of("review this page component for bugs"))
    }
}
