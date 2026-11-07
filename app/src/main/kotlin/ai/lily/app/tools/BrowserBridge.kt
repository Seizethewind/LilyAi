package ai.lily.app.tools

/** The most page text a worker is given. A page can be far longer; the rest is not read. */
const val MAX_PAGE_TEXT = 6_000

/** What the page in the Browse section shows right now. [text] is what a person can read on it, already cut short. */
data class PageSnapshot(val url: String, val title: String, val text: String)

/** One thing the page logged or failed at while loading. [kind] is "console", "error" or "http". */
data class PageEvent(val kind: String, val text: String)

/**
 * How workers see the page in the Browse section. Reading only: nothing here clicks or types.
 * Everything that comes from the page was written by other people, so whatever uses it treats it as data, never as instructions.
 */
interface BrowserBridge {
    /**
     * Brings the Browse page on screen. A page that is not on screen has no size, so layout checks on it would be wrong.
     * False when it could not be shown (for example the app is in the background).
     */
    suspend fun show(): Boolean

    /** The address showing now, or "" when nothing is open. */
    suspend fun currentUrl(): String

    /** Opens [url] and waits until it has loaded or [timeoutMs] has passed. True when it loaded in time without a main page error. */
    suspend fun open(url: String, timeoutMs: Long): Boolean

    suspend fun snapshot(): PageSnapshot?

    suspend fun events(): List<PageEvent>

    suspend fun loadMillis(): Long?

    /** The audit script's answer as JSON text, or null when the page gave none in time. */
    suspend fun audit(): String?
}
