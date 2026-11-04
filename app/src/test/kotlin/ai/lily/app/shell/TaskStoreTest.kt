package ai.lily.app.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TaskStoreTest {
    @Test
    fun savedTasksComeBackExactly() {
        val store = TaskStore(File.createTempFile("tasks", ".json").apply { deleteOnExit() })
        val tasks = listOf("fix the \"login\" bug", "two\nlines")
        store.save(tasks)
        assertEquals(tasks, store.load())
    }

    @Test
    fun aMissingFileLoadsAsNothing() {
        assertEquals(emptyList<String>(), TaskStore(File("/nonexistent/tasks.json")).load())
    }
}
