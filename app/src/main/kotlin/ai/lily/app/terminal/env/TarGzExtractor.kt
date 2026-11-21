package ai.lily.app.terminal.env

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/**
 * Unpacks a .tar.gz root file system (such as Alpine's minirootfs) into [dest], with no library.
 *
 * Rules, because the archive comes from the network:
 * - An entry name with `..` in it, or one that is not plain relative text, is refused.
 * - Nothing is ever written through a link: if a folder on the way to an entry is a link, the archive is refused.
 * - Links are created as they are (an absolute target such as /bin/busybox is correct inside the guest and is never followed
 *   here). Hard links must point at a file already unpacked.
 * - Device files, pipes and sockets are skipped (an app cannot create them, and proot supplies /dev).
 * - Size and entry-count limits stop a decompression bomb.
 *
 * Understands ustar, GNU long names (L, K) and pax headers (path, linkpath, size).
 */
object TarGzExtractor {
    data class Limits(val maxBytes: Long = 4L shl 30, val maxEntries: Int = 200_000)

    data class Result(val entries: Int, val skipped: Int, val bytes: Long)

    fun extract(
        archive: File,
        dest: File,
        limits: Limits = Limits(),
        onEntry: (count: Int) -> Unit = {},
    ): Result {
        dest.mkdirs()
        val destPath = dest.toPath().toAbsolutePath().normalize()
        try {
            GZIPInputStream(BufferedInputStream(archive.inputStream(), 64 * 1024), 64 * 1024).use { gz ->
                return readTar(gz, destPath, limits, onEntry)
            }
        } catch (e: ZipException) {
            throw EnvException(EnvFailure.Archive, "The archive is not a valid .tar.gz: ${e.message}", e)
        } catch (e: EOFException) {
            throw EnvException(EnvFailure.Archive, "The archive ends early (it is cut off).", e)
        } catch (e: EnvException) {
            throw e
        } catch (e: IOException) {
            throw EnvException(EnvFailure.Disk, "Could not unpack: ${e.message}", e)
        }
    }

    private fun readTar(input: InputStream, dest: Path, limits: Limits, onEntry: (Int) -> Unit): Result {
        var entries = 0
        var skipped = 0
        var bytes = 0L
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        val header = ByteArray(BLOCK)

        while (true) {
            if (!readBlock(input, header)) break // clean end of stream without the zero blocks
            if (header.all { it == 0.toByte() }) break // end of archive

            val type = header[156].toInt().toChar()
            var size = paxSize ?: parseSize(header)
            val nameFromHeader = headerName(header)

            when (type) {
                'L' -> { // GNU long name: the data is the name of the next entry
                    longName = readText(input, size, 4096)
                    continue
                }
                'K' -> {
                    longLink = readText(input, size, 4096)
                    continue
                }
                'x' -> { // pax header for the next entry
                    val rec = parsePax(readText(input, size, 64 * 1024))
                    paxPath = rec["path"]
                    paxLink = rec["linkpath"]
                    paxSize = rec["size"]?.toLongOrNull()
                    continue
                }
                'g' -> { // global pax header: nothing in it matters here
                    skip(input, padded(size))
                    continue
                }
            }

            val name = paxPath ?: longName ?: nameFromHeader
            val link = paxLink ?: longLink ?: String(header, 157, 100, Charsets.UTF_8).trimEnd('\u0000')
            longName = null; longLink = null; paxPath = null; paxLink = null; paxSize = null

            entries++
            if (entries > limits.maxEntries) throw archive("The archive has too many entries.")
            onEntry(entries)

            val mode = parseOctal(header, 100, 8).toInt()
            val target = resolve(dest, name)

            when (type) {
                '0', '\u0000', '7' -> {
                    bytes += size
                    if (bytes > limits.maxBytes) throw archive("The archive is larger than allowed once unpacked.")
                    requireNoLinkOnPath(dest, target)
                    target.parent?.let { Files.createDirectories(it) }
                    removeExisting(target)
                    FileOutputStream(target.toFile()).use { out -> copy(input, out, size) }
                    skip(input, padded(size) - size)
                    applyMode(target.toFile(), mode, isDir = false)
                }
                '5' -> {
                    requireNoLinkOnPath(dest, target)
                    Files.createDirectories(target)
                    applyMode(target.toFile(), mode, isDir = true)
                    skip(input, padded(size))
                }
                '2' -> {
                    requireNoLinkOnPath(dest, target)
                    target.parent?.let { Files.createDirectories(it) }
                    removeExisting(target)
                    Files.createSymbolicLink(target, Paths.get(link))
                    skip(input, padded(size))
                }
                '1' -> {
                    val source = resolve(dest, link)
                    requireNoLinkOnPath(dest, target)
                    requireNoLinkOnPath(dest, source)
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        throw archive("A hard link points at \"$link\", which was not unpacked before it.")
                    }
                    target.parent?.let { Files.createDirectories(it) }
                    removeExisting(target)
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
                    skip(input, padded(size))
                }
                else -> { // device, pipe, unknown
                    skipped++
                    skip(input, padded(size))
                }
            }
        }
        return Result(entries, skipped, bytes)
    }

    /** Turns an entry name into a path under [dest], or refuses it. */
    private fun resolve(dest: Path, rawName: String): Path {
        var name = rawName
        while (name.startsWith("./")) name = name.removePrefix("./")
        name = name.trimStart('/').trimEnd('/')
        if (name.isEmpty() || name == ".") return dest
        if ('\u0000' in name) throw archive("An entry name contains a null character.")
        for (part in name.split('/')) {
            if (part == "..") throw archive("An entry name tries to leave the target folder (\"$rawName\").")
        }
        val path = dest.resolve(name).normalize()
        if (!path.startsWith(dest)) throw archive("An entry name resolves outside the target folder (\"$rawName\").")
        return path
    }

    /** Every folder between [dest] and the entry's parent must be a real folder, never a link. */
    private fun requireNoLinkOnPath(dest: Path, target: Path) {
        if (target == dest) return
        var current = target.parent ?: return
        while (current != dest && current.startsWith(dest)) {
            if (Files.isSymbolicLink(current)) {
                throw archive("An entry would be written through a link (\"${dest.relativize(target)}\").")
            }
            current = current.parent ?: return
        }
    }

    private fun removeExisting(target: Path) {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw archive("A file would replace the folder \"${target.fileName}\".")
            }
            Files.delete(target)
        }
    }

    /** Keeps the owner able to read and write (so the tree can be repaired and deleted) and passes the execute bits on. */
    private fun applyMode(file: File, mode: Int, isDir: Boolean) {
        file.setReadable(true, true)
        file.setWritable(true, true)
        val anyExec = mode and 0b001_001_001 != 0
        file.setExecutable(isDir || anyExec, true)
    }

    private fun headerName(h: ByteArray): String {
        val name = String(h, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
        val magic = String(h, 257, 5, Charsets.US_ASCII)
        if (magic == "ustar") {
            val prefix = String(h, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
            if (prefix.isNotEmpty()) return "$prefix/$name"
        }
        return name
    }

    private fun parseSize(h: ByteArray): Long {
        // Base-256 for sizes that do not fit in 11 octal digits.
        if (h[124].toInt() and 0x80 != 0) {
            var v = 0L
            for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        return parseOctal(h, 124, 12)
    }

    private fun parseOctal(h: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        var seen = false
        for (i in off until off + len) {
            val c = h[i].toInt()
            if (c == 0 || c == ' '.code) {
                if (seen) break else continue
            }
            if (c < '0'.code || c > '7'.code) throw archive("A header field is not a number.")
            v = v * 8 + (c - '0'.code)
            seen = true
        }
        return v
    }

    private fun parsePax(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        val bytes = text.toByteArray(Charsets.UTF_8)
        while (i < bytes.size) {
            val sp = bytes.indexOf(' '.code.toByte(), i)
            if (sp < 0) break
            val len = String(bytes, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > bytes.size) break
            val record = String(bytes, sp + 1, i + len - sp - 1, Charsets.UTF_8).trimEnd('\n')
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return out
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    private fun readText(input: InputStream, size: Long, max: Int): String {
        if (size < 0 || size > max) throw archive("A header record is too large.")
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skip(input, padded(size) - size)
        return String(data, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun padded(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("cut off inside a header")
            }
            read += n
        }
        return true
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw EOFException("cut off inside an entry")
            read += n
        }
    }

    private fun copy(input: InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("cut off inside a file")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("cut off inside an entry")
            left -= n
        }
    }

    private fun archive(message: String) = EnvException(EnvFailure.Archive, message)

    private const val BLOCK = 512
}
