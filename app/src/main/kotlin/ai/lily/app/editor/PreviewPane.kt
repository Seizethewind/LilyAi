package ai.lily.app.editor

import ai.lily.app.lib.editor.CONSOLE_MARK
import ai.lily.app.lib.editor.PREVIEW_HOST
import ai.lily.app.lib.editor.buildPreviewDocument
import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.File

private enum class ConsoleLevel { LOG, INFO, WARN, ERROR, DEBUG, INPUT, RESULT }

/** One console line. [path] and [line] say where an error came from in the workspace, so a tap can jump there. */
private data class ConsoleLine(val level: ConsoleLevel, val text: String, val path: String?, val line: Int)

private const val MAX_CONSOLE_LINES = 500

/** The file in the workspace a script address points to, or null for an address outside the preview. */
private fun sourceToPath(source: String?): String? {
    if (source.isNullOrEmpty()) return null
    val uri = Uri.parse(source)
    if (uri.host != PREVIEW_HOST) return null
    return Uri.decode(uri.encodedPath.orEmpty()).trimStart('/').ifEmpty { null }
}

/**
 * Shows [text] (the file at [path]) as a web page and keeps a console under it. The page can load other files of the
 * workspace (stylesheets, scripts, images) by their normal relative addresses; files open in a tab are served with their
 * unsaved text. The page reloads shortly after the text of any open tab changes.
 * It has no access to the app's own data and may only follow links to workspace files.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewPane(
    root: File,
    path: String,
    text: String,
    tabs: List<EditorTab>,
    onJump: (path: String, line: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val openTabs by rememberUpdatedState(tabs)
    val lines = remember { mutableStateListOf<ConsoleLine>() }
    var consoleOpen by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }

    fun push(line: ConsoleLine) {
        lines.add(line)
        while (lines.size > MAX_CONSOLE_LINES) lines.removeAt(0)
    }

    val web = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url ?: return null
                    if (url.host != PREVIEW_HOST) return null
                    val requested = Uri.decode(url.encodedPath.orEmpty())
                    return servePreviewFile(root, requested) { name ->
                        openTabs.firstOrNull { it.path == name }?.value?.text
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return true
                    if (url.host == PREVIEW_HOST) return false
                    push(ConsoleLine(ConsoleLevel.WARN, "Blocked a link to $url. The preview only follows links to your own files.", null, 0))
                    return true
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                    val url = request?.url ?: return
                    val response = errorResponse ?: return
                    val where = if (url.host == PREVIEW_HOST) Uri.decode(url.encodedPath.orEmpty()).trimStart('/') else url.toString()
                    push(ConsoleLine(ConsoleLevel.ERROR, "${response.statusCode} ${response.reasonPhrase.orEmpty()}: $where".trim(), null, 0))
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    val url = request?.url ?: return
                    push(ConsoleLine(ConsoleLevel.ERROR, "Couldn't load $url: ${error?.description ?: "unknown error"}", null, 0))
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val cm = consoleMessage ?: return true
                    val level = when (cm.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.ERROR
                        ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.WARN
                        ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.DEBUG
                        ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.INFO
                        else -> ConsoleLevel.LOG
                    }
                    var message = cm.message().orEmpty()
                    var line = cm.lineNumber()
                    // Lines the page's console helper handled have the line of the helper, not of the call, so they can't be jumped to.
                    if (message.startsWith(CONSOLE_MARK)) {
                        message = message.removePrefix(CONSOLE_MARK)
                        line = 0
                    }
                    push(ConsoleLine(level, message, sourceToPath(cm.sourceId()), line))
                    return true
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }

    val base = remember(path) { "https://$PREVIEW_HOST/" + Uri.encode(path, "/") }
    val document = remember(path, text) { buildPreviewDocument(path, text) }
    // Typing in another open file (a stylesheet, a script) also reloads the page, so its change shows up.
    val othersHash = tabs.fold(0) { acc, t -> if (t.path == path) acc else acc + t.value.text.hashCode() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(document, othersHash, reloadTick) {
        if (loaded) delay(400)
        loaded = true
        lines.clear()
        web.loadDataWithBaseURL(base, document.html, document.mime, "utf-8", null)
    }

    val errorCount = lines.count { it.level == ConsoleLevel.ERROR }
    LaunchedEffect(errorCount) { if (errorCount > 0) consoleOpen = true }

    Column(modifier) {
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
        ConsolePanel(
            lines = lines,
            open = consoleOpen,
            onToggle = { consoleOpen = !consoleOpen },
            onClear = { lines.clear() },
            onReload = { reloadTick++ },
            onRun = { code ->
                push(ConsoleLine(ConsoleLevel.INPUT, code, null, 0))
                web.evaluateJavascript(code) { result -> push(ConsoleLine(ConsoleLevel.RESULT, result ?: "undefined", null, 0)) }
            },
            onJump = onJump,
        )
    }
}

private val CONSOLE_BG = Color(0xFF1E1E24)

private fun levelColors(level: ConsoleLevel): Pair<Color, Color> = when (level) {
    ConsoleLevel.ERROR -> Pair(Color(0xFFFF8A80), Color(0x33B71C1C))
    ConsoleLevel.WARN -> Pair(Color(0xFFFFD54F), Color(0x22FFC107))
    ConsoleLevel.INFO -> Pair(Color(0xFF82B1FF), Color.Transparent)
    ConsoleLevel.DEBUG -> Pair(Color(0xFF9E9EA6), Color.Transparent)
    ConsoleLevel.INPUT -> Pair(Color(0xFFB39DDB), Color.Transparent)
    ConsoleLevel.RESULT -> Pair(Color(0xFFA5D6A7), Color.Transparent)
    ConsoleLevel.LOG -> Pair(Color(0xFFE6E6EA), Color.Transparent)
}

@Composable
private fun CountBadge(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ConsolePanel(
    lines: List<ConsoleLine>,
    open: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onReload: () -> Unit,
    onRun: (String) -> Unit,
    onJump: (path: String, line: Int) -> Unit,
) {
    val errors = lines.count { it.level == ConsoleLevel.ERROR }
    val warnings = lines.count { it.level == ConsoleLevel.WARN }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Console", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (errors > 0) {
                    Spacer(Modifier.size(8.dp))
                    CountBadge("$errors error${if (errors == 1) "" else "s"}", Color(0xFFB71C1C))
                }
                if (warnings > 0) {
                    Spacer(Modifier.size(6.dp))
                    CountBadge("$warnings warning${if (warnings == 1) "" else "s"}", Color(0xFF8A6D00))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onReload) { Text("Reload") }
                TextButton(onClick = onClear) { Text("Clear") }
                Text(
                    text = if (open) "\u25BC" else "\u25B2",
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            if (open) {
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .background(CONSOLE_BG),
                ) {
                    if (lines.isEmpty()) {
                        item {
                            Text(
                                text = "Nothing logged yet. console.log output, errors and missing files show up here.",
                                color = Color(0xFF9E9EA6),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    items(lines) { l ->
                        val (color, background) = levelColors(l.level)
                        val source = l.path
                        val jumpable = source != null && l.line > 0
                        val prefix = when (l.level) {
                            ConsoleLevel.INPUT -> "\u203A "
                            ConsoleLevel.RESULT -> "\u2190 "
                            else -> ""
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(background)
                                .then(if (jumpable) Modifier.clickable { onJump(source!!, l.line) } else Modifier)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                text = prefix + l.text,
                                color = color,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            if (jumpable) {
                                Text(
                                    text = "${source!!.substringAfterLast('/')}:${l.line}",
                                    color = Color(0xFF9E9EA6),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
                var code by remember { mutableStateOf("") }
                val submit = {
                    val c = code.trim()
                    if (c.isNotEmpty()) {
                        onRun(c)
                        code = ""
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CONSOLE_BG)
                        .padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("\u203A", color = Color(0xFFB39DDB), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    BasicTextField(
                        value = code,
                        onValueChange = { code = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        decorationBox = { inner ->
                            Box {
                                if (code.isEmpty()) {
                                    Text(
                                        "Run JavaScript in the page",
                                        color = Color(0xFF6E6E78),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                    TextButton(onClick = { submit() }) { Text("Run") }
                }
            }
        }
    }
}
