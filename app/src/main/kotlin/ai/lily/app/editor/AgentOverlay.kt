package ai.lily.app.editor

import ai.lily.app.lib.editor.DiffKind
import ai.lily.app.lib.editor.foldDiff
import ai.lily.app.lib.editor.lineDiff
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val MAX_DIFF_LINES = 300

/**
 * A small "Ask Lily" button over the editor, a "Review" button when changes are waiting, and the dialogs behind them.
 * Lily sees the open files; what she changes comes back as proposals shown as a diff, and nothing is applied until you accept.
 */
@Composable
fun AgentOverlay(session: AgentSession, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var showAsk by remember { mutableStateOf(false) }
    val pending = session.proposals.size

    Row(
        modifier.navigationBarsPadding().imePadding().padding(end = 12.dp, bottom = 132.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pending > 0) {
            FilledTonalButton(onClick = { session.startReview() }) { Text("Review $pending") }
        }
        Button(onClick = { if (!session.busy) showAsk = true }) {
            Text(if (session.busy) "Lily is working\u2026" else "Ask Lily")
        }
    }

    if (showAsk) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAsk = false },
            title = { Text("Ask Lily") },
            text = {
                Column {
                    Text(
                        "Lily sees your open files, including typing you have not saved. Her changes come back as a proposal you review first.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("What should she change?") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val request = text.trim()
                    if (request.isNotEmpty()) {
                        showAsk = false
                        scope.launch { session.ask(request) }
                    }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { showAsk = false }) { Text("Cancel") } },
        )
    }

    val reply = session.reply
    if (reply != null) {
        val hasProposals = pending > 0
        AlertDialog(
            onDismissRequest = { session.reply = null },
            title = { Text("Lily") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { Text(reply) }
            },
            confirmButton = {
                if (hasProposals) {
                    TextButton(onClick = { session.startReview() }) { Text("Review changes") }
                } else {
                    TextButton(onClick = { session.reply = null }) { Text("Close") }
                }
            },
            dismissButton = {
                if (hasProposals) TextButton(onClick = { session.reply = null }) { Text("Later") }
            },
        )
    }

    if (session.reviewing && pending > 0) {
        AlertDialog(
            onDismissRequest = { session.reviewing = false },
            title = { Text("Review Lily's changes") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    for (p in session.proposals.toList()) {
                        ProposalCard(
                            p,
                            onAccept = { scope.launch { session.accept(p) } },
                            onReject = { session.reject(p) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { scope.launch { session.acceptAll() } }) { Text("Accept all") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { session.rejectAll() }) { Text("Reject all") }
                    TextButton(onClick = { session.reviewing = false }) { Text("Later") }
                }
            },
        )
    }
}

@Composable
private fun ProposalCard(p: Proposal, onAccept: () -> Unit, onReject: () -> Unit) {
    val diff = remember(p) { foldDiff(lineDiff(p.oldText, p.newText)) }
    val shown = remember(diff) { diff.take(MAX_DIFF_LINES) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(p.path + if (p.isNew) " (new file)" else "", fontWeight = FontWeight.Bold)
        Text(p.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            for (l in shown) {
                val (prefix, background) = when (l.kind) {
                    DiffKind.ADD -> "+ " to Color(0x3300C853)
                    DiffKind.DEL -> "- " to Color(0x33D50000)
                    DiffKind.SAME -> "  " to Color.Transparent
                    DiffKind.SKIP -> "" to Color.Transparent
                }
                Text(
                    prefix + l.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (l.kind == DiffKind.SKIP) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    modifier = Modifier.fillMaxWidth().background(background),
                )
            }
            if (diff.size > shown.size) {
                Text(
                    "\u2026 ${diff.size - shown.size} more lines",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row {
            TextButton(onClick = onReject) { Text("Reject") }
            TextButton(onClick = onAccept) { Text("Accept") }
        }
    }
}
