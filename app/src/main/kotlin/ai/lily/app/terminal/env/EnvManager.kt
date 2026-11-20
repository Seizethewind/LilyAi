package ai.lily.app.terminal.env

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Installs, checks, repairs and removes the Linux environment the terminal runs in.
 *
 * An install goes: download (resumable) -> SHA-256 check -> unpack into a staging folder -> swap into place -> write
 * `env.json` last. Because `env.json` is written last and the swap is one rename, a crash at any point leaves either the
 * old environment or none, never a half-unpacked one that looks installed.
 *
 * Pure logic: the download, the free-space check and the unpacker are passed in so tests can fake them.
 */
class EnvManager(
    private val layout: EnvLayout,
    private val downloader: EnvDownloader,
    private val freeSpace: () -> Long = { layout.root.usableSpace },
    private val unpack: (archive: File, dest: File, onEntry: (Int) -> Unit) -> Unit =
        { archive, dest, onEntry -> TarGzExtractor.extract(archive, dest, onEntry = onEntry) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(inspect())
    val state: StateFlow<EnvState> = _state.asStateFlow()

    /** Reads the disk and says what is there. Does not change anything. */
    fun inspect(): EnvState {
        if (!layout.stateFile.isFile) {
            return if (layout.rootfs.exists()) EnvState.Broken("The environment folder has no version record.") else EnvState.Missing
        }
        val meta = try {
            JSONObject(layout.stateFile.readText())
        } catch (e: Exception) {
            return EnvState.Broken("The version record is damaged.")
        }
        val missing = layout.required.firstOrNull { !EnvFiles.existsNoFollow(it) }
        if (missing != null) return EnvState.Broken("${missing.name} is missing from the environment.")
        return EnvState.Ready(meta.optString("version"), meta.optString("arch"))
    }

    /** Refreshes [state] from the disk, for example when the screen opens. */
    fun refresh() {
        if (_state.value !is EnvState.Installing) _state.value = inspect()
    }

    /**
     * Installs [release] for [arch], replacing whatever is there. Progress goes to [state]. Throws [EnvException] on
     * failure after setting [EnvState.Failed]; a cancelled call cleans up and rethrows the cancellation.
     */
    suspend fun install(release: EnvRelease, arch: EnvArch) = lock.withLock {
        val asset = release.assetFor(arch)
            ?: fail(EnvFailure.UnsupportedDevice, "This environment has no build for ${arch.distro}.")
        try {
            layout.root.mkdirs()
            val needed = asset.sizeBytes * SPACE_FACTOR
            if (freeSpace() < needed) {
                fail(EnvFailure.Disk, "Not enough free space: about ${needed / MB} MB is needed.")
            }

            val part = layout.partFile(release.version, arch.distro)
            _state.value = EnvState.Installing(EnvStep.Downloading, part.takeIf { it.isFile }?.length() ?: 0, asset.sizeBytes)
            downloader.download(asset.url, part) { done, total ->
                _state.value = EnvState.Installing(EnvStep.Downloading, done, if (total > 0) total else asset.sizeBytes)
            }

            _state.value = EnvState.Installing(EnvStep.Verifying, 0, 0)
            if (part.length() != asset.sizeBytes || EnvFiles.sha256Hex(part) != asset.sha256) {
                // A wrong file would fail the same way again, so throw it away instead of resuming it.
                part.delete()
                fail(EnvFailure.Checksum, "The download does not match its checksum. It was deleted; try again.")
            }

            _state.value = EnvState.Installing(EnvStep.Unpacking, 0, 0)
            EnvFiles.deleteTree(layout.staging)
            unpack(part, layout.staging) { count -> _state.value = EnvState.Installing(EnvStep.Unpacking, count.toLong(), 0) }
            val missing = layout.required.map { File(layout.staging, it.relativeTo(layout.rootfs).path) }
                .firstOrNull { !EnvFiles.existsNoFollow(it) }
            if (missing != null) fail(EnvFailure.Archive, "The archive has no ${missing.name}, so it is not a usable root file system.")

            _state.value = EnvState.Installing(EnvStep.Finalizing, 0, 0)
            EnvFiles.deleteTree(layout.rootfs)
            if (!layout.staging.renameTo(layout.rootfs)) fail(EnvFailure.Disk, "Could not move the unpacked environment into place.")
            writeState(release.version, arch, asset.sha256)
            part.delete()
            layout.downloads.listFiles()?.forEach { if (it.name.endsWith(".part")) it.delete() }
            _state.value = inspect()
        } catch (e: CancellationException) {
            EnvFiles.deleteTree(layout.staging)
            _state.value = inspect()
            throw e
        } catch (e: EnvException) {
            EnvFiles.deleteTree(layout.staging)
            _state.value = EnvState.Failed(e.failure, e.message.orEmpty())
            throw e
        } catch (e: IOException) {
            EnvFiles.deleteTree(layout.staging)
            val wrapped = EnvException(EnvFailure.Disk, "Disk error: ${e.message}", e)
            _state.value = EnvState.Failed(wrapped.failure, wrapped.message.orEmpty())
            throw wrapped
        }
    }

    /** Installs again only if the environment is missing, broken or older than [release]; returns whether it did. */
    suspend fun ensure(release: EnvRelease, arch: EnvArch): Boolean {
        val current = inspect()
        val upToDate = current is EnvState.Ready && current.version == release.version && current.arch == arch.distro
        if (upToDate) {
            _state.value = current
            return false
        }
        install(release, arch)
        return true
    }

    /** Deletes the environment and any partial download. Fails with [EnvFailure.Disk] if something cannot be removed. */
    suspend fun reset() = lock.withLock {
        try {
            EnvFiles.deleteTree(layout.rootfs)
            EnvFiles.deleteTree(layout.staging)
            EnvFiles.deleteTree(layout.downloads)
            layout.stateFile.delete()
            layout.stateTemp.delete()
            _state.value = inspect()
        } catch (e: IOException) {
            val wrapped = EnvException(EnvFailure.Disk, "Could not remove everything: ${e.message}", e)
            _state.value = EnvState.Failed(wrapped.failure, wrapped.message.orEmpty())
            throw wrapped
        }
    }

    /** Bytes the environment uses on disk (0 when missing). Walks the tree without following links. */
    fun diskUsage(): Long {
        var total = 0L
        if (!layout.root.exists()) return 0
        Files.walkFileTree(layout.root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                total += attrs.size()
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        return total
    }

    private fun writeState(version: String, arch: EnvArch, sha256: String) {
        val json = JSONObject()
            .put("version", version)
            .put("arch", arch.distro)
            .put("sha256", sha256)
            .put("installedAt", now())
        layout.stateTemp.writeText(json.toString())
        Files.move(layout.stateTemp.toPath(), layout.stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun fail(failure: EnvFailure, message: String): Nothing = throw EnvException(failure, message)

    private companion object {
        /** Download plus unpacked size plus headroom, as a multiple of the archive. */
        const val SPACE_FACTOR = 4L
        const val MB = 1024 * 1024
    }
}
