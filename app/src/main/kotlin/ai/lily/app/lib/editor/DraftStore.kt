package ai.lily.app.lib.editor

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Unsaved typing kept on disk. [baseHash] is the hash of the saved text it was typed on top of. */
data class Draft(val path: String, val baseHash: Int, val text: String)

/**
 * Keeps a copy of unsaved typing in [dir] so it survives the app being closed or killed. One file per workspace file,
 * named after a hash of its path. A file holds the base hash on the first line, the path on the second and the text after.
 * A draft is written to a temporary file first and then moved into place, so a crash never leaves half a draft.
 */
class DraftStore(private val dir: File) {

    private fun fileFor(path: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(path.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir, "$name.draft")
    }

    fun save(path: String, text: String, baseHash: Int) {
        if ('\n' in path) throw IOException("Invalid file name")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create the drafts folder")
        val target = fileFor(path)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText("$baseHash\n$path\n$text")
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Couldn't save the draft of $path")
        }
    }

    /** The draft for [path], or null if there is none. */
    fun load(path: String): Draft? {
        val file = fileFor(path)
        if (!file.isFile) return null
        return parse(file.readText())?.takeIf { it.path == path }
    }

    fun delete(path: String) {
        fileFor(path).delete()
    }

    /** Deletes every draft except those for the paths in [keep], and any leftover temporary files. */
    fun pruneExcept(keep: Set<String>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.endsWith(".tmp")) {
                f.delete()
                continue
            }
            val draft = try {
                parse(f.readText())
            } catch (e: IOException) {
                null
            }
            if (draft == null || draft.path !in keep) f.delete()
        }
    }

    private fun parse(raw: String): Draft? {
        val first = raw.indexOf('\n')
        if (first < 0) return null
        val second = raw.indexOf('\n', first + 1)
        if (second < 0) return null
        val hash = raw.substring(0, first).toIntOrNull() ?: return null
        return Draft(raw.substring(first + 1, second), hash, raw.substring(second + 1))
    }
}
