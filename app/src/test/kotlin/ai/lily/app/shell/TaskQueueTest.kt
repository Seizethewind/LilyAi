package ai.lily.app.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskQueueTest {

    @Test
    fun theSecondTaskStartsOnlyAfterTheFirstIsDone() = runBlocking {
        withTimeout(5_000) {
            val release = CompletableDeferred<Unit>()
            val started = mutableListOf<String>()
            val q = TaskQueue(this, run = { t ->
                started += t
                if (t == "one") release.await()
                "ok"
            }, needsReview = { false })
            q.add("one")
            q.add("two")
            yield()
            assertEquals(listOf("one"), started)
            release.complete(Unit)
            while (q.tasks.any { it.state != TaskState.Done }) delay(10)
            assertEquals(listOf("one", "two"), started)
        }
    }

    @Test
    fun aFailedTaskHoldsTheQueueUntilTheUserActs() = runBlocking {
        withTimeout(5_000) {
            val q = TaskQueue(this, run = { t ->
                if (t == "bad") error("boom")
                "ok"
            }, needsReview = { false })
            q.add("bad")
            q.add("next")
            while (q.tasks[0].state != TaskState.NeedsYou) delay(10)
            assertEquals(1, q.needYou)
            assertEquals(TaskState.Queued, q.tasks[1].state)
            q.skip(q.tasks[0])
            while (q.tasks[1].state != TaskState.Done) delay(10)
        }
    }

    @Test
    fun aTaskWithChangesToReviewNeedsYouUntilTheyAreDealtWith() = runBlocking {
        withTimeout(5_000) {
            var review = true
            val q = TaskQueue(this, run = { "ok" }, needsReview = { review })
            q.add("a")
            while (q.tasks[0].state != TaskState.NeedsYou) delay(10)
            assertEquals(1, q.needYou)
            review = false
            while (q.tasks[0].state != TaskState.Done) delay(10)
            assertEquals(0, q.needYou)
        }
    }
}
