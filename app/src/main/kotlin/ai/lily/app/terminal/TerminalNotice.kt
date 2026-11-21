package ai.lily.app.terminal

/** The words in the terminal's notification. Separate from the service so they can be tested. */
object TerminalNotice {
    fun text(running: Int): String = when {
        running <= 0 -> "No shells running"
        running == 1 -> "1 shell is running"
        else -> "$running shells are running"
    }
}
