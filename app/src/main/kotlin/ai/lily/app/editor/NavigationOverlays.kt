package ai.lily.app.editor

import ai.lily.app.lib.editor.FileMatch
import ai.lily.app.lib.editor.FindOptions
import ai.lily.app.lib.editor.ProjectSearchResult
import ai.lily.app.lib.editor.fuzzyRank
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** One row in a picker. [key] says what it is (a file path, a command name) and is what the caller gets back. */
class PickItem(val title: String, val subtitle: String = "", val key: String)

/**
 * A search box over a list that is narrowed as you type (fuzzy: letters in order, not touching).
 * Used for quick open and the command palette. The keyboard's Done key picks the top result.
 */
@Composable
fun PickerDialog(
    title: String,
    placeholder: String,
    options: List<PickItem>,
    scorer: (String, PickItem) -> Int?,
    onDismiss: () -> Unit,
    onPick: (PickItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, options) { fuzzyRank(query, options, 50) { scorer(query, it) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = {
            Column {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(placeholder) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { shown.firstOrNull()?.let(onPick) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (shown.isEmpty()) {
                    Text("No matches", modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(shown) { item ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(item) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.subtitle.isNotEmpty()) {
                                    Text(
                                        item.subtitle,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}

private sealed interface ResultRow {
    class Header(val path: String) : ResultRow
    class Hit(val path: String, val hit: FileMatch) : ResultRow
}

/**
 * Search in all files of the workspace. Files with unsaved typing are searched as they are on screen.
 * With a replacement typed in, the checked files can have every match replaced (after a confirmation).
 */
@Composable
fun SearchAllDialog(
    state: EditorState,
    onDismiss: () -> Unit,
    onOpenHit: (path: String, hit: FileMatch) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ops = LocalWorkspaceOps.current
    var query by remember { mutableStateOf("") }
    var replaceWith by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var useRegex by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<ProjectSearchResult?>(null) }
    var excluded by remember(result) { mutableStateOf(setOf<String>()) }
    var confirmReplace by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun options() = FindOptions(query, caseSensitive = caseSensitive, regex = useRegex)

    fun runSearch() {
        if (query.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                result = state.searchAll(options())
            } catch (e: IllegalArgumentException) {
                error = "Invalid pattern"
                result = null
            }
            busy = false
        }
    }

    val rows = remember(result) {
        buildList<ResultRow> {
            result?.files?.forEach { f ->
                add(ResultRow.Header(f.path))
                f.hits.forEach { add(ResultRow.Hit(f.path, it)) }
            }
        }
    }

    val r = result
    val chosen = r?.files?.map { it.path }?.filter { it !in excluded }.orEmpty()
    val canReplace = ops != null && r != null && !r.truncated && chosen.isNotEmpty() && !busy

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Search in all files") },
        text = {
            Column {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (ops != null) {
                    TextField(
                        value = replaceWith,
                        onValueChange = { replaceWith = it },
                        singleLine = true,
                        placeholder = { Text("Replace with") },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { caseSensitive = !caseSensitive }) {
                        Text("Aa", color = if (caseSensitive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { useRegex = !useRegex }) {
                        Text(".*", color = if (useRegex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { runSearch() }, enabled = query.isNotEmpty() && !busy) {
                        Text(if (busy) "Working\u2026" else "Search")
                    }
                    if (ops != null) {
                        TextButton(onClick = { confirmReplace = true }, enabled = canReplace) {
                            Text("Replace in ${chosen.size}")
                        }
                    }
                }
                when {
                    error != null -> Text(error ?: "")
                    r == null -> Unit
                    r.hitCount == 0 -> Text("No matches in ${r.filesSearched} files")
                    else -> {
                        val more = if (r.truncated) " (showing the first ${r.hitCount})" else ""
                        Text(
                            "${r.hitCount} matches in ${r.files.size} files$more",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (ops != null && r.truncated) {
                            Text(
                                "Too many matches to replace safely. Narrow the search first.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (rows.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(rows) { row ->
                            when (row) {
                                is ResultRow.Header -> Row(
                                    Modifier.fillMaxWidth().padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (ops != null) {
                                        Checkbox(
                                            checked = row.path !in excluded,
                                            onCheckedChange = { on ->
                                                excluded = if (on) excluded - row.path else excluded + row.path
                                            },
                                        )
                                    }
                                    Text(
                                        row.path,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                is ResultRow.Hit -> {
                                    val hit = row.hit
                                    val label = buildAnnotatedString {
                                        append("${hit.line}: ")
                                        val offset = length
                                        append(hit.preview)
                                        val from = (offset + hit.previewStart).coerceAtMost(length)
                                        val to = (from + hit.length).coerceAtMost(length)
                                        if (to > from) addStyle(SpanStyle(background = Color(0x66BD93F9)), from, to)
                                    }
                                    Text(
                                        label,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onOpenHit(row.path, hit) }
                                            .padding(vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )

    if (confirmReplace && ops != null) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace in ${chosen.size} files?") },
            text = {
                Text(
                    "Every match in the checked files is replaced, not only the ones listed. Files open in tabs change in the editor " +
                        "and can be undone. Other files are written to disk and cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReplace = false
                    busy = true
                    scope.launch {
                        val message = ops.replaceInFiles(options(), replaceWith, chosen)
                        state.message = message
                        busy = false
                        onDismiss()
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
}
