package ai.lily.app.workspace

import java.io.File

/**
 * Moves the person to a chosen workspace folder without losing anything: files from the private workspace are copied into it. Nothing is
 * overwritten and nothing is removed from the private workspace, so a failed or partial copy never leaves files only where the person
 * cannot see them. (Ported from the superseded feat/shared-workspace branch, which this replaces with the Settings based folder.)
 */
object WorkspaceMigration {
    data class Result(val copied: Int, val failed: Int)

    /** Whether two folders are the same place, comparing real paths so a symlink or a trailing slash does not fool it. */
    fun sameFolder(a: File, b: File): Boolean =
        runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(a.absoluteFile == b.absoluteFile)

    /**
     * Copies every file of [from] that [to] does not have yet. Returns how many were copied and how many failed.
     * A missing [from], or one that holds [to] (or is held by it), copies nothing, so the walk can never feed on its own copies.
     */
    fun copyMissing(from: File, to: File): Result {
        if (!from.isDirectory) return Result(0, 0)
        if (nested(from, to) || nested(to, from)) return Result(0, 0)
        var copied = 0
        var failed = 0
        for (file in from.walkTopDown().filter { it.isFile }) {
            val dest = File(to, file.relativeTo(from).path)
            if (dest.exists()) continue
            val ok = runCatching {
                dest.parentFile?.mkdirs()
                file.copyTo(dest)
            }.isSuccess
            if (ok) copied++ else failed++
        }
        return Result(copied, failed)
    }

    /** What to tell the person, or null when there was nothing to copy. */
    fun note(result: Result, to: File): String? = when {
        result.failed > 0 -> "Copied ${result.copied} files to ${to.path}; ${result.failed} could not be copied and are still in the private workspace"
        result.copied > 0 -> "Copied ${result.copied} files to ${to.path}"
        else -> null
    }

    private fun nested(inner: File, outer: File): Boolean = runCatching {
        val a = inner.canonicalFile
        val b = outer.canonicalFile
        a != b && a.path.startsWith(b.path + File.separator)
    }.getOrDefault(false)
}
