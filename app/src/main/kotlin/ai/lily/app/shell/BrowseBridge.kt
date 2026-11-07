package ai.lily.app.shell

import ai.lily.app.tools.AuditScript
import ai.lily.app.tools.BrowserBridge
import ai.lily.app.tools.JsResult
import ai.lily.app.tools.MAX_PAGE_TEXT
import ai.lily.app.tools.PageEvent
import ai.lily.app.tools.PageSnapshot
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Lets workers read the page in the Browse section. WebView must be used on the main thread, so every call hops there.
 * [reveal] brings the Browse section on screen: a page that is not on screen has no size, and layout checks on it would be wrong.
 * Nothing here clicks or types.
 */
class BrowseBridge(private val browse: BrowseState, private val reveal: () -> Unit) : BrowserBridge {

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main.immediate) { block() }

    override suspend fun show(): Boolean {
        onMain { reveal() }
        return withTimeoutOrNull(SHOW_TIMEOUT_MS) {
            while (!onMain { browse.web.isAttachedToWindow && browse.web.width > 0 }) delay(50)
            true
        } == true
    }

    override suspend fun currentUrl(): String = onMain { browse.address }

    override suspend fun open(url: String, timeoutMs: Long): Boolean {
        // Arms the clock first, so loadMillis() stays null until the new page has finished.
        // shortcut: an old page that finishes late counts as this one, when that matters count page starts instead.
        browse.log.started(SystemClock.elapsedRealtime())
        onMain { browse.go(url) }
        val finished = withTimeoutOrNull(timeoutMs) {
            while (browse.log.loadMillis() == null) delay(100)
            true
        } == true
        // shortcut: a fixed pause lets scripts draw the page after it "finished", when pages need longer wait for the text to stop changing.
        if (finished) delay(SETTLE_MS)
        return finished && !onMain { browse.failed }
    }

    override suspend fun snapshot(): PageSnapshot? = JsResult.snapshot(js(AuditScript.SNAPSHOT), MAX_PAGE_TEXT)

    override suspend fun events(): List<PageEvent> = browse.log.entries().map { PageEvent(it.kind.name.lowercase(), it.text) }

    override suspend fun loadMillis(): Long? = browse.log.loadMillis()

    override suspend fun audit(): String? = JsResult.text(js(AuditScript.SOURCE))

    /** Runs a script in the page and returns WebView's raw answer, or null if the page did not answer in time. */
    private suspend fun js(script: String): String? = withTimeoutOrNull(JS_TIMEOUT_MS) {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<String?> { cont ->
                browse.web.evaluateJavascript(script) { raw -> if (cont.isActive) cont.resume(raw) }
            }
        }
    }

    private companion object {
        const val SHOW_TIMEOUT_MS = 3_000L
        const val SETTLE_MS = 1_000L
        const val JS_TIMEOUT_MS = 8_000L
    }
}
