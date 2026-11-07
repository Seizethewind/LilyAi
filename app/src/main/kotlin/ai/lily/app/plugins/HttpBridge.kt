package ai.lily.app.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * One finished request: the address already passed [HttpGuard.checkUrl]. [maxBytes] is how much of the answer is read; plugin
 * tools keep the default, and a screen of the app that has to show a long answer (the Discord viewer) may ask for more.
 * With [binary] the answer is kept as bytes in [HttpReply.Done.bytes] (a picture) instead of being read as text.
 */
data class HttpCall(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutMs: Long,
    val permission: Permission.Http,
    val maxBytes: Int = HttpBridge.MAX_RESPONSE_BYTES,
    val binary: Boolean = false,
)

sealed interface HttpReply {
    /**
     * [body] is cut at the call's [HttpCall.maxBytes]; [truncated] says more was left unread. For a [HttpCall.binary] call the
     * body is empty and the answer is in [bytes].
     */
    data class Done(val status: Int, val body: String, val truncated: Boolean, val bytes: ByteArray? = null) : HttpReply

    data class Failed(val reason: FailReason, val message: String) : HttpReply
}

/** How the app sends an https request. [UrlConnectionHttpBridge] is the real one; tests use a fake. */
interface HttpBridge {
    suspend fun call(call: HttpCall): HttpReply

    companion object {
        /** The default amount of an answer that is read. */
        const val MAX_RESPONSE_BYTES = 16 * 1024
    }
}

/** Used when nothing can send requests: every call fails. */
object NoHttpBridge : HttpBridge {
    override suspend fun call(call: HttpCall): HttpReply = HttpReply.Failed(FailReason.NOT_ALLOWED, "this app cannot send http requests here")
}

/**
 * Sends the request with the platform's connection. It refuses a host that resolves to a private or local address, never
 * follows a redirect off the allowed host or path (and follows none for a request that changes something), reads at most
 * the call's [HttpCall.maxBytes] of the answer (16 KB unless the call says otherwise), and times out.
 * Known limit: the address is checked and then the connection resolves the name again, so a host that answers with a
 * public address first and a private one next could slip through. The person has allowed this host by name.
 */
class UrlConnectionHttpBridge : HttpBridge {
    override suspend fun call(call: HttpCall): HttpReply = withContext(Dispatchers.IO) { send(call) }

    private fun send(call: HttpCall): HttpReply {
        try {
            var url = call.url
            var hops = 0
            while (true) {
                val host = URL(url).host
                if (InetAddress.getAllByName(host).any { !HttpGuard.isPublicAddress(it) }) {
                    return HttpReply.Failed(FailReason.NOT_ALLOWED, "$host points at a private or local address")
                }
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = call.method.name
                    conn.connectTimeout = minOf(call.timeoutMs, 15_000L).toInt()
                    conn.readTimeout = call.timeoutMs.toInt()
                    conn.setRequestProperty("User-Agent", "LilyAi-plugin")
                    for ((name, value) in call.headers) conn.setRequestProperty(name, value)
                    if (call.method != HttpMethod.GET) {
                        conn.doOutput = true
                        if (call.body != null && call.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        }
                        conn.outputStream.use { it.write((call.body ?: "").toByteArray(Charsets.UTF_8)) }
                    }
                    val status = conn.responseCode
                    if (status in REDIRECTS) {
                        val next = if (call.method == HttpMethod.GET && hops < MAX_REDIRECTS) {
                            HttpGuard.redirectTarget(conn.getHeaderField("Location"), call.permission)
                        } else {
                            null
                        }
                        if (next == null) {
                            return HttpReply.Failed(FailReason.NOT_ALLOWED, "the server redirected (status $status) somewhere this plugin may not go, so nothing was followed")
                        }
                        url = next
                        hops++
                        continue
                    }
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    return readCapped(status, stream, call.maxBytes, call.binary)
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: SocketTimeoutException) {
            return HttpReply.Failed(FailReason.TIMEOUT, "no answer in time")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            return HttpReply.Failed(FailReason.ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun readCapped(status: Int, stream: java.io.InputStream?, maxBytes: Int, binary: Boolean): HttpReply {
        val out = ByteArrayOutputStream()
        var truncated = false
        stream?.use { input ->
            val chunk = ByteArray(4096)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                val room = maxBytes - total
                if (n > room) {
                    out.write(chunk, 0, room)
                    truncated = true
                    break
                }
                out.write(chunk, 0, n)
                total += n
            }
        }
        return if (binary) {
            HttpReply.Done(status, "", truncated, out.toByteArray())
        } else {
            HttpReply.Done(status, out.toString(Charsets.UTF_8.name()), truncated)
        }
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 3
    }
}
