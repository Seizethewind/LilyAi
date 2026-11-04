package ai.lily.app.shell

import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.ChoiceChip
import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyBloom
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilyComposer
import ai.lily.app.ui.LilyIconButton
import ai.lily.app.ui.LilyMark
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ScreenFrame
import ai.lily.app.ui.SegmentedTabs
import ai.lily.app.ui.StatusDot
import ai.lily.app.ui.TabSpec
import ai.lily.app.ui.Tone
import ai.lily.app.ui.ink
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One line of the conversation. [isError] marks Lily's failure notice, which offers a retry instead of looking like an answer. */
class ChatMessage(val fromUser: Boolean, val text: String, val isError: Boolean = false)

private const val CHAT_HISTORY = 6

/** The free chat with Lily. She has no memory of her own between requests, so the last few messages ride along with each one. */
class ChatState(private val scope: CoroutineScope, private val ask: suspend (String) -> String) {
    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        val history = messages.filter { !it.isError }.takeLast(CHAT_HISTORY)
            .joinToString("\n") { (if (it.fromUser) "User: " else "Lily: ") + it.text }
        messages += ChatMessage(true, t)
        busy = true
        scope.launch {
            try {
                val reply = ask(if (history.isEmpty()) t else "Conversation so far:\n$history\n\nUser: $t")
                messages += ChatMessage(false, reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages += ChatMessage(false, "Lily could not answer: ${e.message ?: e::class.java.simpleName}. Check the router settings.", isError = true)
            } finally {
                busy = false
            }
        }
    }

    /** Asks again after a failure: the notice and the question that caused it are taken out and the question is sent anew. */
    fun retryLast() {
        if (busy || messages.size < 2 || !messages.last().isError) return
        val question = messages[messages.size - 2].text
        messages.removeAt(messages.size - 1)
        messages.removeAt(messages.size - 1)
        send(question)
    }

    /** Starts a fresh conversation. Not while Lily is answering. */
    fun clear() {
        if (!busy) messages.clear()
    }
}

/** The Chat section: free talk with Lily on one tab, the background task queue on the other. */
@Composable
fun ChatScreen(chat: ChatState, queue: TaskQueue, onReview: () -> Unit, modifier: Modifier = Modifier) {
    var tab by remember { mutableStateOf(0) }
    ScreenFrame(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LilyMark(size = 28.dp)
            Text(
                "Lily",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            if (tab == 0 && chat.messages.isNotEmpty() && !chat.busy) {
                LilyIconButton(Icons.Filled.Add, "Start a new chat", onClick = { chat.clear() })
            }
        }
        SegmentedTabs(
            tabs = listOf(TabSpec("Chat"), TabSpec("Tasks", badge = queue.needYou)),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Hairline()
        if (tab == 0) ChatPane(chat) else TaskPane(queue, onReview)
    }
}

private val Suggestions = listOf(
    "Explain the file I have open",
    "Look for bugs in this project",
    "Write tests for the open file",
)

@Composable
private fun ColumnScope.ChatPane(chat: ChatState) {
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val rows = chat.messages.size + if (chat.busy) 1 else 0
    LaunchedEffect(rows) {
        if (rows > 0) list.animateScrollToItem(rows - 1)
    }
    if (chat.messages.isEmpty() && !chat.busy) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            EmptyState(
                title = "What should Lily work on?",
                message = "Ask a question, or describe a change to your project. Lily plans it and hands the parts to her workers.",
                art = { LilyMark(size = 72.dp) },
            )
            Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (suggestion in Suggestions) ChoiceChip(suggestion, selected = false, onClick = { chat.send(suggestion) })
            }
        }
    } else {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(chat.messages) { _, m -> MessageRow(m, onRetry = { chat.retryLast() }) }
            if (chat.busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LilyBloom(size = 24.dp)
                        Text("Lily is working", style = MaterialTheme.typography.bodyMedium, color = LilyUi.colors.textDim, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
    LilyComposer(
        value = text,
        onValueChange = { text = it },
        hint = "Message Lily",
        sendLabel = "Send",
        onSend = {
            chat.send(text)
            text = ""
        },
        enabled = !chat.busy,
    )
}

/** You: a petal-tinted bubble on the right. Lily: plain text on the left under her mark, selectable so it can be copied. */
@Composable
private fun MessageRow(m: ChatMessage, onRetry: () -> Unit) {
    val c = LilyUi.colors
    when {
        m.fromUser -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SelectionContainer {
                Text(
                    m.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                        .background(c.petalSoft)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        m.isError -> Banner(m.text, Tone.Coral, actionLabel = "Try again", onAction = onRetry)
        else -> Row(Modifier.fillMaxWidth()) {
            LilyMark(Modifier.padding(top = 4.dp), size = 20.dp)
            SelectionContainer(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun ColumnScope.TaskPane(queue: TaskQueue, onReview: () -> Unit) {
    var text by remember { mutableStateOf("") }
    if (queue.tasks.isEmpty()) {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            EmptyState(
                title = "Nothing queued",
                message = "Add a task and Lily works through the list in the background, one at a time. She stops and waits when something needs you.",
            )
        }
    } else {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(queue.tasks, key = { it.id }) { task ->
                Column {
                    TaskRow(task, queue, onReview)
                    Hairline(Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
    LilyComposer(
        value = text,
        onValueChange = { text = it },
        hint = "What should Lily do next?",
        sendLabel = "Add task",
        onSend = {
            queue.add(text)
            text = ""
        },
    )
}

/** One queued task: its state on the left, its words and result in the middle, and the actions it is waiting for underneath. */
@Composable
private fun TaskRow(task: UserTask, queue: TaskQueue, onReview: () -> Unit) {
    val c = LilyUi.colors
    val needs = task.state == TaskState.NeedsYou
    val tone = when (task.state) {
        TaskState.Queued -> Tone.Neutral
        TaskState.Running -> Tone.Petal
        TaskState.Done -> Tone.Pad
        TaskState.NeedsYou -> if (task.failed) Tone.Coral else Tone.Stamen
    }
    val status = when (task.state) {
        TaskState.Queued -> "Waiting"
        TaskState.Running -> "Working"
        TaskState.Done -> "Done"
        TaskState.NeedsYou -> if (task.failed) "Needs you: it failed" else "Needs you: review Lily's changes"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (task.state) {
                TaskState.Running -> LilyBloom(size = 22.dp)
                TaskState.Done -> Icon(Icons.Filled.Check, contentDescription = null, tint = tone.ink(), modifier = Modifier.size(18.dp))
                TaskState.NeedsYou -> Icon(Icons.Filled.Warning, contentDescription = null, tint = tone.ink(), modifier = Modifier.size(18.dp))
                TaskState.Queued -> StatusDot(tone)
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(task.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(status, style = MaterialTheme.typography.labelMedium, color = tone.ink(), modifier = Modifier.padding(top = 2.dp))
            if (task.result.isNotBlank() && task.state != TaskState.Queued) {
                Text(
                    task.result.take(600),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textDim,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(12.dp),
                )
            }
            if (needs) {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.failed) {
                        LilyButton("Retry", onClick = { queue.retry(task) }, kind = ButtonKind.Tonal)
                        LilyButton("Skip", onClick = { queue.skip(task) }, kind = ButtonKind.Quiet)
                    } else {
                        LilyButton("Review changes", onClick = onReview)
                    }
                }
            }
        }
    }
}
