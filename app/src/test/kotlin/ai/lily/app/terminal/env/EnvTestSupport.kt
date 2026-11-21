package ai.lily.app.terminal.env

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** Builds small tar archives in memory so the extractor can be tested without real files from the network. */
class TarBuilder {
    private val out = ByteArrayOutputStream()

    fun dir(name: String, mode: Int = 0b111_101_101) = apply { entry('5', name, "", ByteArray(0), mode) }

    fun file(name: String, content: ByteArray = ByteArray(0), mode: Int = 0b110_100_100) =
        apply { entry('0', name, "", content, mode) }

    fun file(name: String, text: String, mode: Int = 0b110_100_100) = file(name, text.toByteArray(), mode)

    fun symlink(name: String, target: String) = apply { entry('2', name, target, ByteArray(0), 0b111_111_111) }

    fun hardlink(name: String, target: String) = apply { entry('1', name, target, ByteArray(0), 0b110_100_100) }

    fun device(name: String) = apply { entry('3', name, "", ByteArray(0), 0b110_000_000) }

    /** A file whose real name travels in a pax header (the header's own name field is a short stand-in). */
    fun fileWithPaxPath(path: String, content: ByteArray) = apply {
        val record = paxRecord("path", path)
        entry('x', "PaxHeader", "", record, 0b110_100_100)
        entry('0', "stand-in", "", content, 0b110_100_100)
    }

    /** A file whose name travels as a GNU long name (type L). */
    fun fileWithGnuLongName(path: String, content: ByteArray) = apply {
        entry('L', "././@LongLink", "", (path + "\u0000").toByteArray(), 0b110_100_100)
        entry('0', "stand-in", "", content, 0b110_100_100)
    }

    fun toTar(): ByteArray = ByteArrayOutputStream().also {
        it.write(out.toByteArray())
        it.write(ByteArray(1024))
    }.toByteArray()

    fun writeGz(file: File, cutOff: Int? = null): File {
        val tar = toTar()
        val bytes = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(tar) } }.toByteArray()
        file.writeBytes(if (cutOff != null) bytes.copyOf(cutOff) else bytes)
        return file
    }

    private fun paxRecord(key: String, value: String): ByteArray {
        val body = " $key=$value\n"
        var len = body.length + 1
        while ((len.toString() + body).length != len) len = (len.toString() + body).length
        return (len.toString() + body).toByteArray()
    }

    private fun entry(type: Char, name: String, link: String, content: ByteArray, mode: Int) {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        put(0, name)
        put(100, "%07o".format(mode))
        put(108, "0000000"); put(116, "0000000")
        put(124, "%011o".format(content.size))
        put(136, "00000000000")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar"); put(263, "00")
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, "%06o".format(sum)); h[154] = 0; h[155] = ' '.code.toByte()
        out.write(h)
        out.write(content)
        val pad = (512 - content.size % 512) % 512
        out.write(ByteArray(pad))
    }
}

/** A downloader that serves bytes from memory and records what it was asked. Honours resume like a server that supports Range. */
class FakeDownloader(var payload: ByteArray = ByteArray(0)) : EnvDownloader {
    var calls = 0
    var existingBytesSeen = -1L
    var interruptAfter: Int? = null
    var manifestText: String = ""

    override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        calls++
        existingBytesSeen = if (dest.isFile) dest.length() else 0
        dest.parentFile?.mkdirs()
        val start = existingBytesSeen.toInt().coerceAtMost(payload.size)
        val end = interruptAfter?.coerceAtMost(payload.size) ?: payload.size
        if (start > 0) dest.appendBytes(payload.copyOfRange(start, end)) else dest.writeBytes(payload.copyOf(end))
        onProgress(end.toLong(), payload.size.toLong())
        if (interruptAfter != null) {
            interruptAfter = null
            throw EnvException(EnvFailure.Network, "interrupted")
        }
    }

    override suspend fun fetchText(url: String, maxBytes: Int): String = manifestText
}

fun sha256Of(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
