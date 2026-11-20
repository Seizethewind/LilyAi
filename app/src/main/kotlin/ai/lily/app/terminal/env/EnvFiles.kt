package ai.lily.app.terminal.env

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Where the environment lives, all under one folder (the app's private files directory in the app). */
class EnvLayout(val root: File) {
    val rootfs = File(root, "rootfs")
    val staging = File(root, "rootfs.new")
    val stateFile = File(root, "env.json")
    val stateTemp = File(root, "env.json.tmp")
    val downloads = File(root, "downloads")

    fun partFile(version: String, distro: String) = File(downloads, "$version-$distro.tar.gz.part")

    /** Files the shell cannot start without. Checked without following links: a link to /bin/busybox only resolves inside the guest. */
    val required: List<File> get() = listOf(File(rootfs, "bin/sh"), File(rootfs, "etc"))
}

object EnvFiles {
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun existsNoFollow(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * Deletes a folder without ever following a link. File.deleteRecursively follows a link that points at a folder, and
     * an unpacked root file system is full of absolute links, so it must not be used here.
     */
    fun deleteTree(file: File) {
        val start: Path = file.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                dir.toFile().setWritable(true, true)
                dir.toFile().setExecutable(true, true)
                dir.toFile().setReadable(true, true)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(f)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
