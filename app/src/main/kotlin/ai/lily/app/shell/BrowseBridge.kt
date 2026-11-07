package ai.lily.app.shell

import ai.lily.app.gateway.ModelImage
import ai.lily.app.tools.AuditScript
import ai.lily.app.tools.BrowserBridge
import ai.lily.app.tools.JsResult
import ai.lily.app.tools.MAX_PAGE_TEXT
import ai.lily.app.tools.PageAction
import ai.lily.app.tools.PageActions
import ai.lily.app.tools.PageElement
import ai.lily.app.tools.PageElements
import ai.lily.app.tools.PageEvent
import ai.lily.app.tools.PageSnapshot
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Lets workers read, and when allowed use, the page in the Browse section. WebView must be used on the main thread, so every call hops there.
 * [reveal] brings the Browse section on screen: a page that is not on screen has no size, and layout checks on it would be wrong.
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

    override suspend fun screenshot(code: String): ModelImage? {
        val bitmap = onMain { capture(code) } ?: return null
        return withContext(Dispatchers.Default) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            ModelImage("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }
    }

    /**
     * Draws what is on screen into a bitmap, scaled to [SHOT_WIDTH] pixels wide, with [code] written in its top-left corner on white.
     * A page that came out all one colour is not returned: that is a failed capture far more often than a real page, and a model
     * asked to judge a blank picture would invent a review of a blank page.
     * shortcut: only what is on screen is drawn, not the whole page, when more is needed scroll and take several.
     */
    private fun capture(code: String): Bitmap? {
        val web = browse.web
        if (web.width <= 0 || web.height <= 0) return null
        return try {
            val full = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(full))
            val scale = minOf(1f, SHOT_WIDTH.toFloat() / full.width)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(full, SHOT_WIDTH, maxOf(1, (full.height * scale).toInt()), true) else full
            if (allOneColour(scaled)) return null
            val shot = scaled.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            val canvas = Canvas(shot)
            val ink = Paint().apply { color = Color.BLACK; textSize = 28f; isAntiAlias = true }
            canvas.drawRect(0f, 0f, ink.measureText(code) + 28f, 42f, Paint().apply { color = Color.WHITE })
            canvas.drawText(code, 14f, 30f, ink)
            shot
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun allOneColour(b: Bitmap): Boolean {
        val pixels = IntArray(b.width * b.height)
        b.getPixels(pixels, 0, b.width, 0, 0, b.width, b.height)
        val first = pixels[0]
        return pixels.all { it == first }
    }

    override suspend fun elements(): List<PageElement>? = PageElements.parse(JsResult.text(js(PageActions.elementsScript())))

    override suspend fun act(action: PageAction): String {
        val blockedBefore = browse.blockedMoves
        val result = PageActions.result(js(PageActions.actionScript(action)))
        if (result == "ok") settle()
        return if (browse.blockedMoves > blockedBefore) "$result (the page tried to move to another site and was stopped)" else result
    }

    /** After a click the page may be loading something new. Waits for it, then a moment for scripts to draw. */
    private suspend fun settle() {
        delay(300)
        withTimeoutOrNull(LOAD_WAIT_MS) {
            while (onMain { browse.loading }) delay(100)
        }
        delay(SETTLE_MS / 2)
    }

    override suspend fun hasCookies(url: String): Boolean = onMain { !CookieManager.getInstance().getCookie(url).isNullOrBlank() }

    override fun lockToHost(host: String?) {
        browse.lockedHost = host
    }

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
        const val LOAD_WAIT_MS = 8_000L
        const val SHOT_WIDTH = 800
    }
}
