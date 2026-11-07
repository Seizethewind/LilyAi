package ai.lily.app.shell

import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.ChoiceChip
import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilyIconButton
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ScreenFrame
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The browser's page, kept alive while the person visits another section. [context] must be the activity.
 * The load state has its own names (loadProgress, not progress) because inside the WebView setup a plain `progress` would mean WebView.progress.
 */
class BrowseState(context: Context) {
    var address by mutableStateOf("")
    var loading by mutableStateOf(false)
    var loadProgress by mutableStateOf(0f)
    var failed by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)

    /** Console messages, failed requests and load time of the current page, for Lily's site checks. */
    val log = PageLog()

    val web: WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading = true
                failed = false
                if (url != null) address = url
                log.started(SystemClock.elapsedRealtime())
                syncHistory(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                if (url != null) address = url
                log.finished(SystemClock.elapsedRealtime())
                syncHistory(view)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                syncHistory(view)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                log.add(PageLog.Kind.Error, "${error?.description} ${request?.url}")
                if (request?.isForMainFrame == true) {
                    failed = true
                    loading = false
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                log.add(PageLog.Kind.Http, "${errorResponse?.statusCode} ${request?.url}")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadProgress = newProgress / 100f
            }

            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message != null) {
                    log.add(PageLog.Kind.Console, "${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                }
                // False keeps Android's own logcat line too.
                return false
            }
        }
    }

    private fun syncHistory(view: WebView?) {
        canBack = view?.canGoBack() == true
        canForward = view?.canGoForward() == true
    }

    /** An address is opened; anything else is searched. */
    fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") -> text
            ' ' !in text && '.' in text -> "https://$text"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(text)
        }
        failed = false
        web.loadUrl(url)
    }

    /** Stops a page that is still loading, or loads it again. */
    fun reloadOrStop() {
        if (loading) web.stopLoading() else web.reload()
    }
}

private val StartLinks = listOf(
    "GitHub" to "https://github.com",
    "Android docs" to "https://developer.android.com",
    "Kotlin docs" to "https://kotlinlang.org/docs/home.html",
    "MDN" to "https://developer.mozilla.org",
)

/** The Browse section: an address and search bar over the page, with a start page until something is opened. */
@Composable
fun BrowseScreen(state: BrowseState, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(state.address) }
    LaunchedEffect(state.address) { text = state.address }
    BackHandler(enabled = state.address.isNotEmpty() && state.canBack) { state.web.goBack() }
    val c = LilyUi.colors
    val s = MaterialTheme.colorScheme

    ScreenFrame(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LilyIconButton(Icons.Filled.ArrowBack, "Back", onClick = { state.web.goBack() }, enabled = state.canBack)
            LilyIconButton(Icons.Filled.ArrowForward, "Forward", onClick = { state.web.goForward() }, enabled = state.canForward)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 4.dp)
                    .clip(CircleShape)
                    .background(c.sunken)
                    .border(1.dp, c.hairline, CircleShape)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (text.startsWith("https://")) {
                    Icon(Icons.Filled.Lock, contentDescription = "Secure connection", tint = c.pad, modifier = Modifier.size(14.dp))
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = s.onBackground),
                    cursorBrush = SolidColor(s.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { state.go(text) }),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text("Search or enter an address", style = MaterialTheme.typography.bodyMedium, color = c.textFaint)
                            inner()
                        }
                    },
                )
                if (text.isNotEmpty()) {
                    LilyIconButton(Icons.Filled.Close, "Clear the address", onClick = { text = "" }, modifier = Modifier.size(40.dp), tint = c.textDim)
                }
            }
            LilyIconButton(
                if (state.loading) Icons.Filled.Close else Icons.Filled.Refresh,
                if (state.loading) "Stop loading" else "Reload",
                onClick = { state.reloadOrStop() },
                enabled = state.address.isNotEmpty(),
            )
        }
        // A hairline that fills with petal colour as the page loads.
        Box(Modifier.fillMaxWidth().height(2.dp).background(c.hairline)) {
            if (state.loading) Box(Modifier.fillMaxWidth(state.loadProgress.coerceIn(0.05f, 1f)).height(2.dp).background(s.primary))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    // The page may still hang under the previous screen's holder after a visit elsewhere.
                    (state.web.parent as? ViewGroup)?.removeView(state.web)
                    state.web
                },
                modifier = Modifier.fillMaxSize(),
            )
            when {
                state.failed -> Box(Modifier.fillMaxSize().background(s.background).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            title = "This page did not load",
                            message = "Check your connection and the address, then try again.",
                            actionLabel = "Try again",
                            onAction = { state.web.reload() },
                        )
                        if (state.canBack) LilyButton("Go back", onClick = { state.web.goBack() }, kind = ButtonKind.Quiet)
                    }
                }
                state.address.isEmpty() && !state.loading -> Box(Modifier.fillMaxSize().background(s.background).pointerInput(Unit) {}, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyState(
                            title = "Look something up",
                            message = "Type a search or an address above, or start from one of these.",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            for ((label, url) in StartLinks) ChoiceChip(label, selected = false, onClick = { state.go(url) })
                        }
                    }
                }
            }
        }
    }
}
