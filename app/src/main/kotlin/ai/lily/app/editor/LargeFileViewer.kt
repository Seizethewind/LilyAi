package ai.lily.app.editor

import ai.lily.app.lib.editor.formatBytes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Shows a file that is too big to edit: one page of lines at a time, with find, go to line, page buttons and a slider. Nothing in it
 * can be changed. The Back button or Close returns to the editor.
 */
@Composable
fun LargeFileViewer(session: LargeFileSession, state: EditorState, onClose: () -> Unit) {
    val theme = state.theme
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    var query by remember { mutableStateOf("") }
    var lineText by remember { mutableStateOf("") }
    var drag by remember { mutableStateOf<Float?>(null) }
    val listState = rememberLazyListState()
    val style = TextStyle(
        fontFamily = state.fontFamily,
        fontSize = state.fontSize.sp,
        lineHeight = (state.fontSize * state.settings.spacing.factor).sp,
        color = theme.foreground,
    )

    // After a search or "go to line" the marked line is brought into view; after paging the page starts at its top.
    LaunchedEffect(session.lines, session.highlightLine) {
        val h = session.highlightLine
        val index = if (h == null) 0 else (h - session.firstLine).coerceIn(0, maxOf(0, session.lines.size - 1))
        listState.scrollToItem(index)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().background(theme.gutterBackground), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${session.name}  \u00b7  ${formatBytes(session.sizeBytes)}  \u00b7  ${session.lineCount} lines  \u00b7  read-only",
                color = theme.foreground,
                fontSize = 13.sp,
                maxLines = 2,
                modifier = Modifier.weight(1f).padding(8.dp),
            )
            TextButton(onClick = onClose) { Text("Close", color = theme.foreground) }
        }

        Row(Modifier.fillMaxWidth().background(theme.gutterBackground), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Find") },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { session.find(query, forward = false) } }) { Text("\u25c0", color = theme.foreground) }
            TextButton(onClick = { scope.launch { session.find(query, forward = true) } }) { Text("\u25b6", color = theme.foreground) }
        }

        Row(
            Modifier.fillMaxWidth().background(theme.gutterBackground).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = lineText,
                onValueChange = { lineText = it.filter(Char::isDigit).take(9) },
                singleLine = true,
                placeholder = { Text("Line") },
                modifier = Modifier.width(110.dp),
            )
            TextButton(onClick = { lineText.toIntOrNull()?.let { n -> scope.launch { session.goToLine(n) } } }) {
                Text("Go", color = theme.foreground)
            }
            TextButton(onClick = { scope.launch { session.load(0) } }) { Text("Top", color = theme.foreground) }
            TextButton(onClick = { scope.launch { session.page(-1) } }) { Text("Page up", color = theme.foreground) }
            TextButton(onClick = { scope.launch { session.page(1) } }) { Text("Page down", color = theme.foreground) }
            TextButton(onClick = { scope.launch { session.load(session.lineCount) } }) { Text("End", color = theme.foreground) }
        }

        if (session.lineCount > 1) {
            Slider(
                value = drag ?: session.firstLine.toFloat(),
                onValueChange = { drag = it },
                onValueChangeFinished = {
                    val target = drag
                    drag = null
                    if (target != null) scope.launch { session.load(target.toInt()) }
                },
                valueRange = 0f..(session.lineCount - 1).toFloat(),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(session.lines) { i, text ->
                val lineNo = session.firstLine + i
                val marked = lineNo == session.highlightLine
                Row(Modifier.fillMaxWidth().then(if (marked) Modifier.background(theme.cursor.copy(alpha = 0.25f)) else Modifier)) {
                    Text(
                        "${lineNo + 1}",
                        style = style.copy(color = theme.gutterForeground, textAlign = TextAlign.End),
                        modifier = Modifier.width(72.dp).padding(end = 8.dp),
                    )
                    Text(text.ifEmpty { " " }, style = style, modifier = Modifier.weight(1f))
                }
            }
        }

        val shown = session.lines.size
        Text(
            (session.status?.let { "$it    " } ?: "") +
                if (shown == 0) "Empty file" else "Lines ${session.firstLine + 1}\u2013${session.firstLine + shown} of ${session.lineCount}",
            color = theme.gutterForeground,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().background(theme.gutterBackground).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
