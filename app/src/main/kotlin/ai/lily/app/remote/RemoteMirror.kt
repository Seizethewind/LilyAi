package ai.lily.app.remote

import ai.lily.app.codeindex.CodeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class MirrorLimits(
    val maxFiles: Int = 3_000,
    val maxFileBytes: Long = 1_000_000L,
    val maxTotalBytes: Long = 100_000_000L,
    val maxDepth: Int = 24,
)

/**
 * What a pull did. [skipped] maps a path to the reason it was left out. [localKept] lists files the person changed on the
 * phone that the server also changed or removed: they were left alone. [complete] is false when the listing or a download was
 * cut short, in which case nothing was deleted locally.
 */
data class PullReport(
    val downloaded: List<String>,
    val unchanged: Int,
    val skipped: Map<String, String>,
    val localKept: List<String>,
    val removed: List<String>,
    val complete: Boolean,
)

enum class ConflictKind {
    /** The server's copy changed since the last pull or push. */
    REMOTE_CHANGED,

    /** The server's copy was deleted. */
    REMOTE_DELETED,

    /** A file that was never pulled or pushed already exists on the server. */
    REMOTE_APPEARED,
}

sealed class PushResult {
    data class Uploaded(val path: String) : PushResult()
    data class Conflict(val path: String, val kind: ConflictKind) : PushResult()
    data class Failed(val path: String, val message: String) : PushResult()
}

/**
 * A local copy of a remote folder. The editor, search and the code index work on the local copy exactly as on any workspace.
 * [pull] brings the server's changes down without overwriting what the person changed; [push] sends one saved file up, and
 * refuses when the server's copy changed behind the phone's back unless told to overwrite.
 */
class RemoteMirror(
    private val fs: RemoteFs,
    private val root: File,
    private val manifestFile: File,
    private val limits: MirrorLimits = MirrorLimits(),
) {
    private val lock = Mutex()

    private class Walk(val files: Map<String, RemoteEntry>, val skipped: MutableMap<String, String>, val complete: Boolean)

    private suspend fun loadManifest(): MutableMap<String, Baseline> = withContext(Dispatchers.IO) {
        val text = if (manifestFile.isFile) runCatching { manifestFile.readText() }.getOrDefault("") else ""
        ManifestCodec.decode(text)
    }

    private suspend fun saveManifest(baselines: Map<String, Baseline>) = withContext(Dispatchers.IO) {
        writeAtomically(manifestFile, ManifestCodec.encode(baselines).toByteArray())
    }

    private suspend fun walk(): Walk {
        val files = linkedMapOf<String, RemoteEntry>()
        val skipped = linkedMapOf<String, String>()
        var complete = true
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add("" to 0)
        while (pending.isNotEmpty()) {
            val (dir, depth) = pending.removeFirst()
            val entries = try {
                fs.list(dir)
            } catch (e: RemoteException) {
                complete = false
                skipped[dir.ifEmpty { "/" }] = "could not list: ${e.message}"
                continue
            }
            for (e in entries.sortedBy { it.path }) {
                val name = RemotePaths.nameOf(e.path)
                if (!RemotePaths.isSafeName(name)) {
                    skipped[RemotePaths.child(dir, name.ifEmpty { "?" })] = "unsafe name"
                    continue
                }
                val path = RemotePaths.child(dir, name)
                when {
                    e.isLink -> skipped[path] = "link"
                    e.isDir -> when {
                        name in CodeFilter.SKIP_DIRS -> skipped["$path/"] = "skipped folder"
                        depth >= limits.maxDepth -> {
                            skipped["$path/"] = "too deep"
                            complete = false
                        }
                        else -> pending.add(path to depth + 1)
                    }
                    files.size >= limits.maxFiles -> {
                        skipped[path] = "file limit reached"
                        complete = false
                    }
                    else -> files[path] = e.copy(path = path)
                }
            }
        }
        return Walk(files, skipped, complete)
    }

    /** Brings the server's changes down. Never overwrites or deletes a file the person changed on the phone. */
    suspend fun pull(): PullReport = lock.withLock {
        val baselines = loadManifest()
        val walk = walk()
        val skipped = walk.skipped
        val downloaded = mutableListOf<String>()
        val localKept = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var unchanged = 0
        var total = 0L
        var complete = walk.complete

        for ((path, entry) in walk.files) {
            if (entry.size > limits.maxFileBytes) {
                skipped[path] = "larger than ${limits.maxFileBytes} bytes"
                continue
            }
            if (total + entry.size > limits.maxTotalBytes) {
                skipped[path] = "download budget used up"
                complete = false
                continue
            }
            val local = RemotePaths.resolveLocal(root, path)
            if (local == null) {
                skipped[path] = "unsafe path"
                continue
            }
            val base = baselines[path]
            val exists = withContext(Dispatchers.IO) { local.isFile }
            if (base != null && exists && base.size == entry.size && base.modified == entry.modified) {
                unchanged++
                continue
            }
            val localHash = if (exists) withContext(Dispatchers.IO) { Hashing.sha256Hex(local) } else null
            if (base != null && localHash != null && localHash != base.hash) {
                localKept += path
                continue
            }
            val bytes = try {
                fs.read(path, limits.maxFileBytes)
            } catch (e: RemoteException) {
                skipped[path] = "could not read: ${e.message}"
                complete = false
                continue
            }
            val remoteHash = Hashing.sha256Hex(bytes)
            if (base == null && localHash != null && localHash != remoteHash) {
                localKept += path
                continue
            }
            if (localHash == remoteHash) {
                unchanged++
            } else {
                try {
                    withContext(Dispatchers.IO) { writeAtomically(local, bytes) }
                } catch (e: IOException) {
                    skipped[path] = "could not write: ${e.message}"
                    complete = false
                    continue
                }
                downloaded += path
            }
            baselines[path] = Baseline(bytes.size.toLong(), entry.modified, remoteHash)
            total += bytes.size
        }

        if (walk.complete) {
            for ((path, base) in baselines.toMap()) {
                if (path in walk.files) continue
                val local = RemotePaths.resolveLocal(root, path)
                val present = local != null && withContext(Dispatchers.IO) { local.isFile }
                if (!present) {
                    baselines.remove(path)
                } else if (withContext(Dispatchers.IO) { Hashing.sha256Hex(local!!) } == base.hash) {
                    withContext(Dispatchers.IO) { local!!.delete() }
                    baselines.remove(path)
                    removed += path
                } else {
                    localKept += path
                }
            }
        }

        saveManifest(baselines)
        PullReport(downloaded, unchanged, skipped, localKept, removed, complete)
    }

    private suspend fun ensureRemoteFolders(dir: String) {
        if (dir.isEmpty()) return
        var prefix = ""
        for (part in dir.split('/')) {
            prefix = RemotePaths.child(prefix, part)
            val s = fs.stat(prefix)
            if (s == null) {
                fs.mkdir(prefix)
            } else if (!s.isDir) {
                throw RemoteException(RemoteFailure.FAILED, "$prefix is a file on the server")
            }
        }
    }

    /**
     * Sends one saved file. Without [force] it refuses when the server's copy is not the one the phone last saw, so another
     * person's or another tool's change is never silently overwritten.
     */
    suspend fun push(path: String, force: Boolean = false): PushResult = lock.withLock {
        val local = RemotePaths.resolveLocal(root, path)
            ?: return@withLock PushResult.Failed(path, "That path is outside the workspace")
        val bytes = withContext(Dispatchers.IO) {
            when {
                !local.isFile -> null
                local.length() > limits.maxFileBytes -> null
                else -> local.readBytes()
            }
        } ?: return@withLock PushResult.Failed(path, "Not a file, or larger than ${limits.maxFileBytes} bytes")

        val baselines = loadManifest()
        val base = baselines[path]
        try {
            val remote = fs.stat(path)
            if (remote != null && remote.isDir) return@withLock PushResult.Failed(path, "A folder with that name exists on the server")
            if (!force) {
                val kind = when {
                    base == null && remote != null -> ConflictKind.REMOTE_APPEARED
                    base != null && remote == null -> ConflictKind.REMOTE_DELETED
                    base != null && remote != null && (remote.size != base.size || remote.modified != base.modified) ->
                        ConflictKind.REMOTE_CHANGED
                    else -> null
                }
                if (kind != null) return@withLock PushResult.Conflict(path, kind)
            }
            ensureRemoteFolders(RemotePaths.parent(path))
            fs.write(path, bytes)
            val after = fs.stat(path)
            if (after != null && after.size != bytes.size.toLong()) {
                return@withLock PushResult.Failed(path, "The server holds ${after.size} bytes after upload, expected ${bytes.size}")
            }
            if (after == null) {
                baselines.remove(path)
            } else {
                baselines[path] = Baseline(bytes.size.toLong(), after.modified, Hashing.sha256Hex(bytes))
            }
            saveManifest(baselines)
            PushResult.Uploaded(path)
        } catch (e: RemoteException) {
            PushResult.Failed(path, e.message ?: "Upload failed")
        } catch (e: IOException) {
            PushResult.Failed(path, e.message ?: "Upload failed")
        }
    }

    /** Replaces the local copy with the server's, for when the person chooses the server's version in a conflict. Returns a problem, or null. */
    suspend fun takeRemote(path: String): String? = lock.withLock {
        val local = RemotePaths.resolveLocal(root, path) ?: return@withLock "That path is outside the workspace"
        val baselines = loadManifest()
        try {
            val remote = fs.stat(path)
            if (remote == null) {
                withContext(Dispatchers.IO) { local.delete() }
                baselines.remove(path)
            } else if (remote.isDir) {
                return@withLock "A folder with that name exists on the server"
            } else {
                val bytes = fs.read(path, limits.maxFileBytes)
                withContext(Dispatchers.IO) { writeAtomically(local, bytes) }
                baselines[path] = Baseline(bytes.size.toLong(), remote.modified, Hashing.sha256Hex(bytes))
            }
            saveManifest(baselines)
            null
        } catch (e: RemoteException) {
            e.message ?: "Could not read the server's copy"
        } catch (e: IOException) {
            e.message ?: "Could not write the local copy"
        }
    }
}
