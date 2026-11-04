package ai.lily.app.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ChatMessage(val fromUser: Boolean, val text: String)

private const val CHAT_HISTORY = 6

/** The free chat with Lily. She has no memory of her own between requests, so the last few messages ride along with each one. */
class ChatState(private val scope: CoroutineScope, private val ask: suspend (String) -> String) {
    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        val history = messages.takeLast(CHAT_HISTORY).joinToString("\n") { (if (it.fromUser) "User: " else "Lily: ") + it.text }
        messages += ChatMessage(true, t)
        busy = true
        scope.launch {
            val reply = try {
                ask(if (history.isEmpty()) t else "Conversation so far:\n$history\n\nUser: $t")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Lily could not answer: ${e.message ?: e::class.java.simpleName}. Check the router settings."
            }
            messages += ChatMessage(false, reply)
            busy = false
        }
    }
}

/** The Chat section: free talk with Lily on one tab, the background task queue on the other. */
@Composable
fun ChatScreen(chat: ChatState, queue: TaskQueue, onReview: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { tab = 0 }) { Text("Chat", fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
            TextButton(onClick = { tab = 1 }) {
                val need = if (queue.needYou > 0) " (${queue.needYou} need you)" else ""
                Text("Tasks$need", fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal)
            }
        }
        if (tab == 0) ChatPane(chat) else TaskPane(queue, onReview)
    }
}

@Composable
private fun ColumnScope.ChatPane(chat: ChatState) {
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) list.animateScrollToItem(chat.messages.lastIndex)
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
        items(chat.messages) { m ->
            Text(
                (if (m.fromUser) "You: " else "Lily: ") + m.text,
                modifier = Modifier.padding(vertical = 6.dp),
                color = if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            )
        }
    }
    if (chat.busy) Text("Lily is thinking...", style = MaterialTheme.typography.bodySmall)
    InputRow(text, { text = it }, "Message Lily", "Send", enabled = !chat.busy) {
        chat.send(text)
        text = ""
    }
}

@Composable
private fun ColumnScope.TaskPane(queue: TaskQueue, onReview: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Text(
        "Tasks run one after another in the background. Lily stops and waits when a task needs you.",
        style = MaterialTheme.typography.bodySmall,
    )
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        items(queue.tasks, key = { it.id }) { task -> TaskCard(task, queue, onReview) }
    }
    InputRow(text, { text = it }, "New task for Lily", "Add", enabled = true) {
        queue.add(text)
        text = ""
    }
}

@Composable
private fun TaskCard(task: UserTask, queue: TaskQueue, onReview: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(task.text, style = MaterialTheme.typography.titleSmall)
        val needs = task.state == TaskState.NeedsYou
        Text(
            when (task.state) {
                TaskState.Queued -> "Waiting"
                TaskState.Running -> "Working..."
                TaskState.Done -> "Done"
                TaskState.NeedsYou -> if (task.failed) "Needs you: it failed" else "Needs you: review Lily's changes"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (needs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (task.result.isNotBlank() && task.state != TaskState.Queued) {
            Text(task.result.take(600), style = MaterialTheme.typography.bodySmall)
        }
        if (needs) {
            Row {
                if (task.failed) {
                    TextButton(onClick = { queue.retry(task) }) { Text("Retry") }
                    TextButton(onClick = { queue.skip(task) }) { Text("Skip") }
                } else {
                    TextButton(onClick = onReview) { Text("Review changes") }
                }
            }
        }
    }
}

@Composable
private fun InputRow(value: String, onChange: (String) -> Unit, hint: String, button: String, enabled: Boolean, onSend: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(value, onChange, placeholder = { Text(hint) }, modifier = Modifier.weight(1f))
        TextButton(onClick = onSend, enabled = enabled && value.isNotBlank()) { Text(button) }
    }
}
