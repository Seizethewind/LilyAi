package ai.lily.app.terminal.env

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Fetches one file into [dest]. Tests replace it; the app uses [HttpEnvDownloader]. */
interface EnvDownloader {
    /**
     * If [dest] already holds part of the file it continues from there when the server allows, otherwise starts over.
     * [onProgress] gets bytes so far and the server's total (0 when unknown). The caller checks the SHA-256 afterwards.
     */
    suspend fun download(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit)

    /** Small text file, such as the release manifest. */
    suspend fun fetchText(url: String, maxBytes: Int = 64 * 1024): String
}

class HttpEnvDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : EnvDownloader {

    override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        requireHttps(url)
        dest.parentFile?.mkdirs()
        val have = if (dest.isFile) dest.length() else 0L
        withContext(Dispatchers.IO) {
            val conn = open(url).apply { if (have > 0) setRequestProperty("Range", "bytes=$have-") }
            try {
                val code = conn.responseCode
                when {
                    // The file is already complete; the checksum decides whether it is the right one.
                    code == 416 -> {
                        onProgress(have, have)
                        return@withContext
                    }
                    code == 206 -> Unit
                    code == 200 -> if (have > 0) dest.delete()
                    else -> throw EnvException(EnvFailure.Network, "The download server answered $code.")
                }
                val append = code == 206
                val start = if (append) have else 0L
                val length = conn.contentLengthLong
                val total = if (length > 0) start + length else 0L
                FileOutputStream(dest, append).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = start
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                        out.fd.sync()
                    }
                }
            } catch (e: EnvException) {
                throw e
            } catch (e: IOException) {
                throw EnvException(EnvFailure.Network, "The download was interrupted: ${e.message}", e)
            } finally {
                conn.disconnect()
            }
        }
    }

    override suspend fun fetchText(url: String, maxBytes: Int): String {
        requireHttps(url)
        return withContext(Dispatchers.IO) {
            val conn = open(url)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw EnvException(EnvFailure.Network, "The server answered $code.")
                // Read by hand: InputStream.readNBytes only exists from Android 13, and the app supports Android 8.
                val out = java.io.ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw EnvException(EnvFailure.BadManifest, "The manifest is too large.")
                    }
                }
                out.toString("UTF-8")
            } catch (e: EnvException) {
                throw e
            } catch (e: IOException) {
                throw EnvException(EnvFailure.Network, "Could not reach the server: ${e.message}", e)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
        }

    private fun requireHttps(url: String) {
        if (!url.startsWith("https://")) throw EnvException(EnvFailure.BadManifest, "Only https downloads are allowed.")
    }
}
