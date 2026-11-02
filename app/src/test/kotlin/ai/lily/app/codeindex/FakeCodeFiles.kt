package ai.lily.app.codeindex

/** An in-memory project for tests. [reads] records every file the index asked to read. */
class FakeCodeFiles : CodeFiles {
    private val texts = LinkedHashMap<String, String>()
    private val stampOf = LinkedHashMap<String, FileStamp>()
    val reads = ArrayList<String>()
    var truncated = false
    var failure: Exception? = null

    fun put(path: String, text: String, modified: Long = 1L) {
        texts[path] = text
        stampOf[path] = FileStamp(text.length.toLong(), modified)
    }

    /** Changes only the stamp, as if the file was saved again with the same text. */
    fun touch(path: String, modified: Long) {
        stampOf[path] = FileStamp(stampOf.getValue(path).size, modified)
    }

    fun remove(path: String) {
        texts.remove(path)
        stampOf.remove(path)
    }

    override suspend fun stamps(): CodeStamps {
        failure?.let { throw it }
        return CodeStamps(LinkedHashMap(stampOf), truncated)
    }

    override suspend fun read(path: String): String? {
        reads += path
        return texts[path]
    }
}
