package ai.lily.app.sandbox

import java.util.Base64

data class BundleLimits(
    val maxFiles: Int = 2_000,
    val maxFileBytes: Long = 1_000_000L,
    val maxTotalBytes: Long = 8_000_000L,
    /** Longest single command sent to the sandbox. */
    val maxCommandChars: Int = 100_000,
    /** Base64 characters of one file written per piece. Must divide by 4 so every piece decodes on its own. */
    val pieceChars: Int = 60_000,
) {
    init {
        require(pieceChars > 0 && pieceChars % 4 == 0) { "pieceChars must be a positive multiple of 4" }
        require(maxCommandChars >= pieceChars + 2_000) { "maxCommandChars must leave room for a piece plus paths" }
    }
}

/** The files that would be uploaded, and the ones left out with a reason. */
class BundlePlan(val files: List<VerifyFile>, val skipped: List<String>)

/** Shell commands that recreate the workspace in the sandbox under [WorkspaceBundler.WORK_DIR]. Run them in order. */
class WorkspaceBundle(val commands: List<String>, val files: List<String>, val totalBytes: Long, val skipped: List<String>)

sealed interface BundleResult {
    class Ready(val bundle: WorkspaceBundle) : BundleResult

    /** Too big to upload whole. Nothing is uploaded: a half-copied project would fail in confusing ways. */
    class TooLarge(val reason: String) : BundleResult
}

/**
 * Turns a workspace into upload commands. Pandastack has no file upload call, so every file travels inside an exec
 * command as base64 and is decoded there. Secrets are filtered out ([VerifyFilter]). Unsaved text from the editor can
 * replace a file ([overrides], null text means deleted), so a change that was accepted but not yet saved is verified too.
 */
object WorkspaceBundler {
    const val WORK_DIR = "/tmp/work"
    private const val MAX_PATH_CHARS = 400

    suspend fun plan(files: VerifyFiles, overrides: Map<String, String?>, limits: BundleLimits = BundleLimits()): BundlePlan {
        val byPath = LinkedHashMap<String, VerifyFile>()
        for (f in files.list()) {
            if (VerifyFilter.acceptsPath(f.path) && f.path.length <= MAX_PATH_CHARS) byPath[f.path] = f
        }
        for ((path, text) in overrides) {
            if (!VerifyFilter.acceptsPath(path) || path.length > MAX_PATH_CHARS) continue
            if (text == null) byPath.remove(path) else byPath[path] = VerifyFile(path, text.toByteArray(Charsets.UTF_8).size.toLong())
        }
        val skipped = ArrayList<String>()
        val kept = byPath.values.sortedBy { it.path }.filter { f ->
            if (f.size > limits.maxFileBytes) {
                skipped += "${f.path} (${f.size} bytes, over the per-file limit)"
                false
            } else {
                true
            }
        }
        return BundlePlan(kept, skipped)
    }

    suspend fun build(files: VerifyFiles, overrides: Map<String, String?>, limits: BundleLimits = BundleLimits()): BundleResult {
        val plan = plan(files, overrides, limits)
        if (plan.files.size > limits.maxFiles) {
            return BundleResult.TooLarge("The project has ${plan.files.size} files to upload; the limit is ${limits.maxFiles}.")
        }
        if (plan.files.sumOf { it.size } > limits.maxTotalBytes) {
            return BundleResult.TooLarge("The project is larger than the ${limits.maxTotalBytes} byte upload limit.")
        }
        val skipped = plan.skipped.toMutableList()
        val loaded = ArrayList<Pair<String, ByteArray>>()
        var total = 0L
        for (f in plan.files) {
            val bytes = if (overrides.containsKey(f.path)) overrides[f.path]?.toByteArray(Charsets.UTF_8) else files.read(f.path)
            if (bytes == null) {
                skipped += "${f.path} (could not be read)"
                continue
            }
            total += bytes.size
            if (total > limits.maxTotalBytes) {
                return BundleResult.TooLarge("The project is larger than the ${limits.maxTotalBytes} byte upload limit.")
            }
            loaded += f.path to bytes
        }
        return BundleResult.Ready(WorkspaceBundle(commands(loaded, limits), loaded.map { it.first }, total, skipped))
    }

    private fun q(text: String) = ShellQuote.quote(text)

    private fun commands(files: List<Pair<String, ByteArray>>, limits: BundleLimits): List<String> {
        val segments = ArrayList<String>()
        segments += "rm -rf ${q(WORK_DIR)} && mkdir -p ${q(WORK_DIR)}"
        val madeDirs = HashSet<String>()
        for ((path, bytes) in files) {
            val dir = path.substringBeforeLast('/', "")
            if (dir.isNotEmpty() && madeDirs.add(dir)) segments += "mkdir -p ${q("$WORK_DIR/$dir")}"
            val target = q("$WORK_DIR/$path")
            if (bytes.isEmpty()) {
                segments += ": > $target"
                continue
            }
            val encoded = Base64.getEncoder().encodeToString(bytes)
            var from = 0
            while (from < encoded.length) {
                val piece = encoded.substring(from, minOf(from + limits.pieceChars, encoded.length))
                val redirect = if (from == 0) ">" else ">>"
                segments += "printf '%s' '$piece' | base64 -d $redirect $target"
                from += limits.pieceChars
            }
            if (bytes.size >= 2 && bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()) segments += "chmod +x $target"
        }
        // Pack segments into as few commands as fit, joined with && so a failed step stops the rest.
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (s in segments) {
            if (cur.isNotEmpty() && cur.length + SEPARATOR.length + s.length > limits.maxCommandChars) {
                out += cur.toString()
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append(SEPARATOR)
            cur.append(s)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private const val SEPARATOR = " && "
}
