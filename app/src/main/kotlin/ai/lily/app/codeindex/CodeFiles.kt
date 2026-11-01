package ai.lily.app.codeindex

/** What a file looked like when it was last read: its size and when it was last changed. Equal stamps mean "probably the same text". */
data class FileStamp(val size: Long, val modified: Long)

/** Every file worth indexing, with its stamp. [truncated] is true when there were more files than the limit allowed. */
data class CodeStamps(val files: Map<String, FileStamp>, val truncated: Boolean = false)

/** Where the index reads a workspace from. Paths are relative to the workspace and use `/`. */
interface CodeFiles {
    /** The candidate files and their stamps. Cheap: it must not read file contents. */
    suspend fun stamps(): CodeStamps

    /** The saved text of a file, or null if it cannot be read. */
    suspend fun read(path: String): String?
}

/** Which files are worth indexing: source and text, not build output, dependencies, binaries or minified code. */
object CodeFilter {
    const val MAX_FILE_BYTES = 200_000L
    const val MAX_FILES = 3_000
    private const val MAX_LINE_CHARS = 2_000

    val SKIP_DIRS = setOf(
        ".git", "node_modules", "build", ".gradle", ".idea", "dist", "out", "target", ".next", "__pycache__", ".venv", "venv",
    )

    private val SKIP_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "svg", "pdf", "zip", "gz", "tar", "jar", "aar", "apk", "so", "class", "dex",
        "woff", "woff2", "ttf", "otf", "mp3", "mp4", "wav", "ogg", "webm", "mov", "keystore", "jks", "lock", "map",
    )

    private val SKIP_NAMES = setOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", ".ds_store")

    /** False for a path inside a skipped folder, with a skipped extension, a lock file or minified code. */
    fun acceptsPath(path: String): Boolean {
        val parts = path.split('/')
        if (parts.dropLast(1).any { it in SKIP_DIRS }) return false
        val name = parts.last().lowercase()
        if (name in SKIP_NAMES || name.endsWith(".min.js") || name.endsWith(".min.css")) return false
        return name.substringAfterLast('.', "") !in SKIP_EXTENSIONS
    }

    fun acceptsSize(size: Long): Boolean = size in 1..MAX_FILE_BYTES

    /** True for text that is not hand-written code: it holds a NUL character (binary) or a line over 2,000 characters (minified). */
    fun looksGenerated(text: String): Boolean {
        var run = 0
        for (c in text) {
            if (c == '\u0000') return true
            if (c == '\n') run = 0 else if (++run > MAX_LINE_CHARS) return true
        }
        return false
    }
}
