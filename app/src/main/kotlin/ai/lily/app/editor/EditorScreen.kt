package ai.lily.app.editor

import ai.lily.app.lib.editor.Lang
import ai.lily.app.lib.editor.canPreview
import ai.lily.app.lib.editor.fuzzyPathScore
import ai.lily.app.lib.editor.fuzzyScore
import ai.lily.app.lib.editor.highlight
import ai.lily.app.lib.editor.lineColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LocalEditorTheme = compositionLocalOf { EDITOR_THEMES.first() }

private class HighlightTransformation(
    private val lang: Lang,
    private val theme: EditorTheme,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val b = AnnotatedString.Builder(s)
        for (sp in highlight(s, lang)) {
            b.addStyle(theme.style(sp.kind), sp.start, sp.end)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** An entry the host adds to the command palette (for example "Ask Lily"). */
class ExtraCommand(val title: String, val hint: String = "", val run: () -> Unit)

/** One entry in the command palette. */
private class Command(val title: String, val hint: String = "", val run: () -> Unit)

@Composable
fun EditorScreen(state: EditorState, extraCommands: List<ExtraCommand> = emptyList()) {
    val theme = state.theme
    CompositionLocalProvider(LocalEditorTheme provides theme) {
        EditorContent(state, theme, extraCommands)
    }
}

@Composable
private fun EditorContent(state: EditorState, theme: EditorTheme, extraCommands: List<ExtraCommand>) {
    val scope = rememberCoroutineScope()
    var showOpen by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var showQuick by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var showSearchAll by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val tab = state.current

    // Brings back the tabs from last time, then every moment keeps a copy of unsaved typing and does auto save.
    LaunchedEffect(Unit) {
        state.restoreSession()
        while (true) {
            delay(1_500)
            withContext(Dispatchers.IO) { state.flushDrafts() }
            state.autoSaveTick()
        }
    }

    val previewable = tab != null && canPreview(tab.path)
    val mode = if (previewable) state.preview else PreviewMode.OFF

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .onPreviewKeyEvent { e ->
                // Hardware keyboard shortcuts.
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.P -> {
                        if (e.isShiftPressed) showPalette = true else showQuick = true
                        true
                    }

                    Key.F -> {
                        if (e.isShiftPressed) showSearchAll = true else if (tab != null) showFind = true
                        true
                    }

                    Key.S -> {
                        tab?.let { t -> scope.launch { state.save(t) } }
                        true
                    }

                    Key.G -> {
                        if (tab != null) showGoto = true
                        true
                    }

                    else -> false
                }
            },
    ) {
        // File actions
        Row(Modifier.fillMaxWidth().background(theme.gutterBackground).horizontalScroll(rememberScrollState())) {
            BarButton("Files") { showQuick = true }
            BarButton("Commands") { showPalette = true }
            BarButton("Search all") { showSearchAll = true }
            BarButton("Open") { showOpen = true }
            BarButton("New") { showNew = true }
            BarButton("Save", enabled = tab != null) { tab?.let { scope.launch { state.save(it) } } }
            BarButton("Save all", enabled = state.tabs.any { it.dirty }) { scope.launch { state.saveAll() } }
            BarButton("Find", enabled = tab != null) { showFind = !showFind }
            BarButton(
                when (mode) {
                    PreviewMode.OFF -> "Preview"
                    PreviewMode.SPLIT -> "Preview: split"
                    PreviewMode.FULL -> "Preview: full"
                },
                enabled = previewable,
            ) { state.cyclePreview() }
            BarButton("Settings") { showSettings = true }
        }

        // Tabs
        if (state.tabs.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth().background(theme.gutterBackground)) {
                itemsIndexed(state.tabs) { i, t ->
                    Row(
                        Modifier
                            .background(if (i == state.active) theme.background else theme.gutterBackground)
                            .clickable { state.active = i }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t.name + if (t.dirty) " •" else "",
                            color = if (i == state.active) theme.foreground else theme.gutterForeground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "  ✕",
                            color = theme.gutterForeground,
                            modifier = Modifier.clickable { state.close(t) }.padding(4.dp),
                        )
                    }
                }
            }
        }

        if (showFind && tab != null) FindBar(state)

        state.message?.let { msg ->
            Text(
                msg,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF44475A))
                    .clickable { state.message = null }
                    .padding(8.dp),
            )
        }

        // Editor
        if (mode != PreviewMode.FULL) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (tab == null) {
                    Text(
                        "Open a file or create a new one",
                        color = theme.gutterForeground,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    key(tab) { CodeArea(tab, state, Modifier.fillMaxSize()) }
                }
            }
        }

        // Preview and console
        if (mode != PreviewMode.OFF && tab != null) {
            key(tab.path) {
                PreviewPane(
                    root = state.root,
                    path = tab.path,
                    text = tab.value.text,
                    tabs = state.tabs,
                    onJump = { p, line ->
                        if (state.preview == PreviewMode.FULL) state.preview = PreviewMode.SPLIT
                        scope.launch { state.openAt(p, line, 1, 0) }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }

        if (tab != null) {
            val pos = lineColumn(tab.value.text, tab.value.selection.start)
            Text(
                "${tab.lang.label}    Ln ${pos.first}, Col ${pos.second}",
                color = theme.gutterForeground,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.gutterBackground)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            QuickTools(state, onGoto = { showGoto = true })
        }
    }

    if (showOpen) {
        var files by remember { mutableStateOf<List<String>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            files = state.listFiles()
            loaded = true
        }
        AlertDialog(
            onDismissRequest = { showOpen = false },
            confirmButton = { TextButton(onClick = { showOpen = false }) { Text("Close") } },
            title = { Text("Open file") },
            text = {
                when {
                    !loaded -> Text("Loading…")
                    files.isEmpty() -> Text("The workspace is empty. Use New to create a file.")
                    else -> LazyColumn {
                        items(files) { f ->
                            Text(
                                f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showOpen = false
                                        scope.launch { state.open(f) }
                                    }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
        )
    }

    if (showQuick) {
        var files by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) { files = state.listFiles() }
        val opened = state.tabs.map { it.path }
        // Files that are already open come first.
        val options = remember(files, opened) {
            (opened + files.filter { it !in opened }).map {
                PickItem(title = it.substringAfterLast('/'), subtitle = it.substringBeforeLast('/', ""), key = it)
            }
        }
        PickerDialog(
            title = "Quick open",
            placeholder = "File name",
            options = options,
            scorer = { q, item -> fuzzyPathScore(q, item.key) },
            onDismiss = { showQuick = false },
            onPick = { item ->
                showQuick = false
                scope.launch { state.open(item.key) }
            },
        )
    }

    if (showPalette) {
        val commands = listOf(
            Command("Quick open file", "Ctrl+P") { showQuick = true },
            Command("Open file list") { showOpen = true },
            Command("New file") { showNew = true },
            Command("Save", "Ctrl+S") { tab?.let { scope.launch { state.save(it) } } },
            Command("Save all") { scope.launch { state.saveAll() } },
            Command("Find and replace", "Ctrl+F") { if (tab != null) showFind = true },
            Command("Search and replace in all files", "Ctrl+Shift+F") { showSearchAll = true },
            Command("Go to line", "Ctrl+G") { if (tab != null) showGoto = true },
            Command("Toggle preview") { state.cyclePreview() },
            Command("Editor settings") { showSettings = true },
            Command("Undo") { state.undo() },
            Command("Redo") { state.redo() },
            Command("Duplicate line") { state.duplicateLine() },
            Command("Delete line") { state.deleteLine() },
            Command("Toggle comment") { state.toggleComment() },
            Command("Indent") { state.indent() },
            Command("Unindent") { state.unindent() },
            Command("Next tab") { state.switchTab(1) },
            Command("Previous tab") { state.switchTab(-1) },
            Command("Close tab") { tab?.let { state.close(it) } },
        ) + extraCommands.map { Command(it.title, it.hint, it.run) }
        val options = remember(extraCommands) { commands.map { PickItem(title = it.title, subtitle = it.hint, key = it.title) } }
        PickerDialog(
            title = "Commands",
            placeholder = "Type a command",
            options = options,
            scorer = { q, item -> fuzzyScore(q, item.title) },
            onDismiss = { showPalette = false },
            onPick = { item ->
                showPalette = false
                commands.firstOrNull { it.title == item.key }?.run?.invoke()
            },
        )
    }

    if (showSearchAll) {
        SearchAllDialog(
            state = state,
            onDismiss = { showSearchAll = false },
            onOpenHit = { path, hit ->
                showSearchAll = false
                scope.launch { state.openAt(path, hit.line, hit.column, hit.length) }
            },
        )
    }

    if (showSettings) SettingsDialog(state) { showSettings = false }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNew = false },
            confirmButton = {
                TextButton(onClick = {
                    showNew = false
                    scope.launch { state.create(name) }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
            title = { Text("New file") },
            text = {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("src/main.py") },
                )
            },
        )
    }

    if (showGoto) {
        var lineText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGoto = false },
            confirmButton = {
                TextButton(onClick = {
                    showGoto = false
                    lineText.toIntOrNull()?.let { state.goToLine(it) }
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showGoto = false }) { Text("Cancel") } },
            title = { Text("Go to line") },
            text = {
                TextField(
                    value = lineText,
                    onValueChange = { lineText = it.filter(Char::isDigit) },
                    singleLine = true,
                )
            },
        )
    }
}

@Composable
private fun BarButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val theme = LocalEditorTheme.current
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, color = if (enabled) theme.foreground else theme.gutterForeground)
    }
}

@Composable
private fun CodeArea(tab: EditorTab, state: EditorState, modifier: Modifier) {
    val theme = LocalEditorTheme.current
    val settings = state.settings
    val value = tab.value
    val wrap = settings.wordWrap
    val showNumbers = settings.lineNumbers && !wrap
    val lineCount = value.text.count { it == '\n' } + 1
    val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val big = value.text.length > MAX_HIGHLIGHT_CHARS
    val transformation = remember(tab.lang, big, theme) {
        if (big) VisualTransformation.None else HighlightTransformation(tab.lang, theme)
    }
    val codeStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = state.fontSize.sp,
        lineHeight = (state.fontSize * settings.spacing.factor).sp,
        color = theme.foreground,
    )
    val horizontal = rememberScrollState()
    Row(modifier.verticalScroll(rememberScrollState())) {
        if (showNumbers) {
            Text(
                gutter,
                style = codeStyle.copy(color = theme.gutterForeground, textAlign = TextAlign.End),
                modifier = Modifier
                    .background(theme.gutterBackground)
                    .padding(horizontal = 8.dp)
                    .widthIn(min = 24.dp),
            )
        }
        // Without word wrap the field sits in a horizontal scroll area, so it gets unbounded width and lines do not wrap.
        Box(Modifier.weight(1f).then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal))) {
            BasicTextField(
                value = value,
                onValueChange = { state.onEdit(tab, it) },
                modifier = if (wrap) {
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 400.dp).padding(horizontal = 8.dp)
                } else {
                    Modifier.defaultMinSize(minWidth = 320.dp, minHeight = 400.dp).padding(end = 24.dp)
                },
                textStyle = codeStyle,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                visualTransformation = transformation,
                cursorBrush = SolidColor(theme.cursor),
            )
        }
    }
}

@Composable
private fun FindBar(state: EditorState) {
    val theme = LocalEditorTheme.current
    Column(Modifier.fillMaxWidth().background(theme.gutterBackground).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.findQuery,
                onValueChange = {
                    state.findQuery = it
                    state.findInfo = null
                },
                singleLine = true,
                placeholder = { Text("Find") },
                modifier = Modifier.weight(1f),
            )
            BarButton("◀") { state.findNext(false) }
            BarButton("▶") { state.findNext(true) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.replaceText,
                onValueChange = { state.replaceText = it },
                singleLine = true,
                placeholder = { Text("Replace with") },
                modifier = Modifier.weight(1f),
            )
            BarButton("Replace") { state.replaceCurrent() }
            BarButton("All") { state.replaceAll() }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.caseSensitive = !state.caseSensitive }) {
                Text("Aa", color = if (state.caseSensitive) theme.cursor else theme.gutterForeground)
            }
            TextButton(onClick = { state.useRegex = !state.useRegex }) {
                Text(".*", color = if (state.useRegex) theme.cursor else theme.gutterForeground)
            }
            state.findInfo?.let { Text(it, color = theme.foreground, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun QuickTools(state: EditorState, onGoto: () -> Unit) {
    val theme = LocalEditorTheme.current
    val symbols = listOf("(", ")", "{", "}", "[", "]", ";", ":", "\"", "'", "<", ">", "/", "=", "_", "\\", "|", "&", "#")
    Column(Modifier.fillMaxWidth().background(theme.gutterBackground)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            BarButton("Undo", enabled = state.current?.history?.canUndo == true) { state.undo() }
            BarButton("Redo", enabled = state.current?.history?.canRedo == true) { state.redo() }
            BarButton("Line") { onGoto() }
            BarButton("Dup") { state.duplicateLine() }
            BarButton("Del") { state.deleteLine() }
            BarButton("//") { state.toggleComment() }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            BarButton("Tab") { state.indent() }
            BarButton("⇤") { state.unindent() }
            for (s in symbols) BarButton(s) { state.insert(s) }
        }
    }
}
