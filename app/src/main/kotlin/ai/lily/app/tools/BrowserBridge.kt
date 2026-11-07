package ai.lily.app.tools

import ai.lily.app.gateway.ModelImage

/** The most page text a worker is given. A page can be far longer; the rest is not read. */
const val MAX_PAGE_TEXT = 6_000

/** What the page in the Browse section shows right now. [text] is what a person can read on it, already cut short. */
data class PageSnapshot(val url: String, val title: String, val text: String)

/** One thing the page logged or failed at while loading. [kind] is "console", "error" or "http". */
data class PageEvent(val kind: String, val text: String)

/**
 * One thing on the page that can be clicked or typed into. [index] is its place in the list; [label] is the short name the page
 * gives it, sent back with an action so a page that changed since the list was made is noticed. [detail] is the rest (type, hint).
 */
data class PageElement(val index: Int, val kind: String, val label: String, val detail: String)

/** What a worker may do to a page. Nothing else exists: no scripts of its own, no moving to another address. */
sealed interface PageAction {
    data class Click(val index: Int, val label: String) : PageAction
    data class Type(val index: Int, val label: String, val text: String) : PageAction
}

/**
 * How workers see the page in the Browse section. Reading comes first; the few things that act on the page ([act], [lockToHost]) have
 * defaults that do nothing, so a browser that cannot act is still a valid one.
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

    /**
     * A picture of what is on screen, with [code] written in its top-left corner so a reply can prove the picture was seen.
     * Null when no picture could be made, or when the page came out blank.
     */
    suspend fun screenshot(code: String): ModelImage? = null

    /** What can be clicked or typed into, or null when the page gave no list in time. */
    suspend fun elements(): List<PageElement>? = null

    /** Does [action] and waits for the page to settle. Returns what happened, starting with "ok" or "error" or "refused". */
    suspend fun act(action: PageAction): String = "error: this browser cannot act on pages"

    /** True when this browser holds cookies for [url], which means a login there would be used. */
    suspend fun hasCookies(url: String): Boolean = false

    /** While set, the page cannot move to any other host. Null lifts it. */
    fun lockToHost(host: String?) {}
}
