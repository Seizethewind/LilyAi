package ai.lily.app.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TaskState { Queued, Running, NeedsYou, Done }

/** One thing the person asked Lily to do in the background. [failed] tells a NeedsYou task that crashed from one waiting for review. */
class UserTask(val id: Int, val text: String) {
    var state by mutableStateOf(TaskState.Queued)
    var result by mutableStateOf("")
    var failed by mutableStateOf(false)
}

/**
 * Tasks run one at a time, in the order they were added. A task that failed, or that left changes for the person to review,
 * is NeedsYou and holds the queue: the next one starts only once this one is Done. [run] must throw when the task failed.
 * [needsReview] says whether Lily's changes are still waiting for the person.
 */
class TaskQueue(
    private val scope: CoroutineScope,
    private val run: suspend (String) -> String,
    private val needsReview: () -> Boolean,
) {
    val tasks = mutableStateListOf<UserTask>()
    val needYou: Int get() = tasks.count { it.state == TaskState.NeedsYou }

    /** True while anything is queued, running or waiting for the person: the app must be kept alive. */
    val busy: Boolean get() = tasks.any { it.state != TaskState.Done }

    /** What would be lost if the app were killed now. These are saved and queued again at the next start. */
    val unfinished: List<String> get() = tasks.filter { it.state != TaskState.Done }.map { it.text }

    private var nextId = 1
    private var runner: Job? = null

    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        tasks += UserTask(nextId++, t)
        if (runner?.isActive != true) runner = scope.launch { loop() }
    }

    /** Puts a failed task back in line. It is the oldest waiting one, so it runs next. */
    fun retry(task: UserTask) {
        if (task.state != TaskState.NeedsYou || !task.failed) return
        task.failed = false
        task.state = TaskState.Queued
    }

    fun skip(task: UserTask) {
        if (task.state != TaskState.NeedsYou) return
        task.result = "Skipped"
        task.state = TaskState.Done
    }

    private suspend fun loop() {
        while (true) {
            val task = tasks.firstOrNull { it.state == TaskState.Queued } ?: return
            task.state = TaskState.Running
            try {
                task.result = run(task.text)
                task.state = if (needsReview()) TaskState.NeedsYou else TaskState.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                task.result = "Could not finish: ${e.message ?: e::class.java.simpleName}"
                task.failed = true
                task.state = TaskState.NeedsYou
            }
            // shortcut: polls every 300 ms while waiting for the person, switch to a snapshotFlow if it ever costs battery.
            while (task.state == TaskState.NeedsYou) {
                if (!task.failed && !needsReview()) task.state = TaskState.Done else delay(300)
            }
        }
    }
}
