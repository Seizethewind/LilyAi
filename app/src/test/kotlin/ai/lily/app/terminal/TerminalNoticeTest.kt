package ai.lily.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalNoticeTest {
    @Test fun saysHowManyShellsAreRunning() {
        assertEquals("No shells running", TerminalNotice.text(0))
        assertEquals("1 shell is running", TerminalNotice.text(1))
        assertEquals("3 shells are running", TerminalNotice.text(3))
    }
}
