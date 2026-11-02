package ai.lily.app.sandbox

import ai.lily.app.codeindex.CodeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/** A workspace file as the verifier sees it. [path] is relative to the workspace and uses `/`. */
data class VerifyFile(val path: String, val size: Long)

/** Where the verifier reads the project from. Bytes, not text, because a Gradle wrapper jar must reach the sandbox intact. */
interface VerifyFiles {
    /** Candidate files with their sizes. Must not read contents. */
    suspend fun list(): List<VerifyFile>

    /** The saved bytes of a file, or null if it cannot be read. */
    suspend fun read(path: String): ByteArray?
}

/** Which files may leave the device for the sandbox. Secrets never do. */
object VerifyFilter {
    val SKIP_DIRS: Set<String> = CodeFilter.SKIP_DIRS + setOf(".kotlin", ".cxx", ".ssh", ".aws", ".gnupg", ".terraform")

    private val SECRET_EXTENSIONS = setOf("pem", "key", "p12", "pfx", "jks", "keystore")
    private val SECRET_NAMES = setOf(
        ".npmrc", ".netrc", ".pypirc", "local.properties", "credentials.json", "service-account.json", "google-services.json",
    )
    private val ENV_TEMPLATES = setOf(".env.example", ".env.sample", ".env.template", ".env.dist")

    /** False for a path that is empty, absolute, or climbs out of the workspace, or holds characters that do not belong in a path. */
    fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")) return false
        if (path.any { it == '\u0000' || it == '\n' || it == '\r' || it == '\\' }) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    fun acceptsPath(path: String): Boolean {
        if (!isSafePath(path)) return false
        val parts = path.split('/')
        if (parts.dropLast(1).any { it in SKIP_DIRS }) return false
        val name = parts.last().lowercase()
        if (name == ".ds_store") return false
        if (name.startsWith(".env") && name !in ENV_TEMPLATES) return false
        if (name in SECRET_NAMES || name.startsWith("id_rsa") || name.startsWith("id_ed25519") || name.contains("secret")) return false
        return name.substringAfterLast('.', "") !in SECRET_EXTENSIONS
    }
}

/** Reads the workspace folder on disk. Symbolic links are not followed, so nothing outside the folder can be uploaded. */
class DiskVerifyFiles(root: File, private val maxReadBytes: Long = 5_000_000L) : VerifyFiles {
    private val base = root.canonicalFile

    override suspend fun list(): List<VerifyFile> = withContext(Dispatchers.IO) {
        val out = ArrayList<VerifyFile>()
        base.walkTopDown()
            .onEnter { dir -> dir == base || (dir.name !in VerifyFilter.SKIP_DIRS && !Files.isSymbolicLink(dir.toPath())) }
            .forEach { f ->
                if (out.size >= WALK_LIMIT) return@forEach
                if (!f.isFile || Files.isSymbolicLink(f.toPath())) return@forEach
                val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
                if (VerifyFilter.acceptsPath(rel)) out.add(VerifyFile(rel, f.length()))
            }
        out
    }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!VerifyFilter.acceptsPath(path)) return@withContext null
        val f = File(base, path).canonicalFile
        if (!f.path.startsWith(base.path + File.separator) || !f.isFile || f.length() > maxReadBytes) return@withContext null
        runCatching { f.readBytes() }.getOrNull()
    }

    private companion object {
        const val WALK_LIMIT = 20_000
    }
}
