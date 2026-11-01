package ai.lily.app.editor

import ai.lily.app.lib.editor.fuzzyPathScore
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** The dialogs for working on files and folders. */
sealed interface WsDialog {
    data object NewFolder : WsDialog
    data object PickRename : WsDialog
    data object PickDelete : WsDialog
    class Rename(val path: String) : WsDialog
    class ConfirmDelete(val path: String, val detail: String) : WsDialog
}

/** Which overlay dialogs are open. Commands in the palette set these. */
class EditorUi {
    var ask by mutableStateOf(false)
    var dialog by mutableStateOf<WsDialog?>(null)
}

/** Lets dialogs deep in the editor reach the workspace operations. Null when there are none. */
val LocalWorkspaceOps = compositionLocalOf<WorkspaceOps?> { null }

@Composable
fun WorkspaceDialogs(ops: WorkspaceOps, ui: EditorUi) {
    val scope = rememberCoroutineScope()
    when (val d = ui.dialog) {
        null -> Unit

        WsDialog.NewFolder -> NameDialog(
            title = "New folder",
            initial = "",
            placeholder = "src/utils",
            confirm = "Create",
            onDismiss = { ui.dialog = null },
        ) { name ->
            ui.dialog = null
            scope.launch { ops.newFolder(name) }
        }

        WsDialog.PickRename -> EntryPicker(
            title = "Rename which file or folder?",
            ops = ops,
            onDismiss = { ui.dialog = null },
        ) { path -> ui.dialog = WsDialog.Rename(path.trimEnd('/')) }

        WsDialog.PickDelete -> EntryPicker(
            title = "Delete which file or folder?",
            ops = ops,
            onDismiss = { ui.dialog = null },
        ) { path ->
            scope.launch {
                val detail = ops.describe(path)
                ui.dialog = WsDialog.ConfirmDelete(path.trimEnd('/'), detail)
            }
        }

        is WsDialog.Rename -> NameDialog(
            title = "Rename or move",
            initial = d.path,
            placeholder = "new/path.kt",
            confirm = "Rename",
            onDismiss = { ui.dialog = null },
        ) { name ->
            ui.dialog = null
            scope.launch { ops.rename(d.path, name) }
        }

        is WsDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { ui.dialog = null },
            title = { Text("Delete ${d.path}?") },
            text = { Text(d.detail) },
            confirmButton = {
                TextButton(onClick = {
                    ui.dialog = null
                    scope.launch { ops.delete(d.path) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { ui.dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EntryPicker(
    title: String,
    ops: WorkspaceOps,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var entries by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { entries = ops.listEntries() }
    val options = remember(entries) {
        entries.map { e ->
            val trimmed = e.trimEnd('/')
            PickItem(
                title = trimmed.substringAfterLast('/') + if (e.endsWith("/")) "/" else "",
                subtitle = trimmed.substringBeforeLast('/', ""),
                key = e,
            )
        }
    }
    PickerDialog(
        title = title,
        placeholder = "Name",
        options = options,
        scorer = { q, item -> fuzzyPathScore(q, item.key) },
        onDismiss = onDismiss,
        onPick = { onPick(it.key) },
    )
}
