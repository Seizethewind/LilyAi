package ai.lily.app.remote

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Names and paths that come from a server are untrusted: a hostile server could list a file called `..` or one with a slash
 * in its name to make the app write outside its cache. Nothing from a server touches the disk before it passes here.
 */
object RemotePaths {
    fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        return name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
    }

    /** A non-empty relative path whose every segment is a safe name. */
    fun isSafePath(path: String): Boolean = path.isNotEmpty() && path.split('/').all { isSafeName(it) }

    fun child(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun nameOf(path: String): String = path.substringAfterLast('/')

    /** The local file for a remote path, or null when the path is unsafe or would land outside [root]. */
    fun resolveLocal(root: File, path: String): File? {
        if (!isSafePath(path)) return null
        val base = root.canonicalFile
        val file = File(base, path).canonicalFile
        return if (file.path.startsWith(base.path + File.separator)) file else null
    }
}

internal object Hashing {
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }
}

/** Writes next to the target and then moves it into place, so a crash never leaves half a file. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    val dir = target.parentFile ?: throw IOException("No folder for ${target.name}")
    dir.mkdirs()
    val part = File(dir, target.name + ".lily-part")
    part.writeBytes(bytes)
    try {
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        part.delete()
        throw e
    }
}
