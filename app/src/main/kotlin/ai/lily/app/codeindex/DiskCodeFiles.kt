package ai.lily.app.codeindex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A workspace folder on disk. It reads the saved files, not what is typed in the editor, so the index describes the project as
 * it is on disk. Files open in tabs are shown to the model separately.
 */
class DiskCodeFiles(private val root: File) : CodeFiles {
    override suspend fun stamps(): CodeStamps = withContext(Dispatchers.IO) {
        val base = root.canonicalFile
        val found = LinkedHashMap<String, FileStamp>()
        var truncated = false
        for (f in base.walkTopDown().onEnter { it == base || it.name !in CodeFilter.SKIP_DIRS }.filter { it.isFile }) {
            val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
            if (!CodeFilter.acceptsPath(rel)) continue
            val size = f.length()
            if (!CodeFilter.acceptsSize(size)) continue
            if (found.size >= CodeFilter.MAX_FILES) {
                truncated = true
                break
            }
            found[rel] = FileStamp(size, f.lastModified())
        }
        CodeStamps(found, truncated)
    }

    override suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        val base = root.canonicalFile
        val f = File(base, path).canonicalFile
        if (!f.path.startsWith(base.path + File.separator) || !f.isFile || !CodeFilter.acceptsSize(f.length())) return@withContext null
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return@withContext null
        String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
    }
}
