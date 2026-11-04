package ai.lily.app.shell

import org.json.JSONArray
import java.io.File

/** The unfinished tasks, kept in a file so a killed app can queue them again. Best effort: a failed write only loses the saved copy. */
class TaskStore(private val file: File) {
    fun load(): List<String> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    fun save(texts: List<String>) {
        runCatching { file.writeText(JSONArray(texts).toString()) }
    }
}
