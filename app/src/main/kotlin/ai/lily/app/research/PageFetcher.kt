package ai.lily.app.research

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

/** What one HTTP GET returned. [body] holds at most the byte limit that was asked for; [truncated] says if more was left unread. */
class RawResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
    val truncated: Boolean = false,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * One GET, without following redirects (the caller does that, checking each address). A seam for tests, and for a transport that
 * connects to the address [UrlGuard] checked.
 */
interface HttpTransport {
    suspend fun get(url: String, maxBytes: Int): RawResponse
}

/** The page could not be read. The message is fit to show the person. */
class FetchException(message: String) : Exception(message)

/** A fetched page: [url] is where it ended up after redirects. */
data class FetchedPage(val url: String, val title: String, val text: String, val truncated: Boolean)

interface PageFetcher {
    /** Throws [UnsafeUrlException] if the address (or a redirect) may not be fetched, [FetchException] if the page cannot be read. */
    suspend fun fetch(url: String): FetchedPage
}

/**
 * Fetches a page safely: the address is checked by [guard] first and again for every redirect (at most [maxRedirects]); the body
 * is cut at [maxBytes]; only HTML, XHTML and plain text are read (a PDF, an image or a download is refused); the text is extracted
 * and cut at [maxChars].
 */
class GuardedPageFetcher(
    private val guard: UrlGuard,
    private val transport: HttpTransport,
    private val maxBytes: Int = 1_500_000,
    private val maxChars: Int = 60_000,
    private val maxRedirects: Int = 4,
) : PageFetcher {

    override suspend fun fetch(url: String): FetchedPage {
        var current = guard.check(url)
        var hops = 0
        while (true) {
            val response = transport.get(current, maxBytes)
            if (response.code in REDIRECTS) {
                val location = response.header("Location")?.trim().orEmpty()
                if (location.isEmpty()) throw FetchException("The page redirected without saying where.")
                hops += 1
                if (hops > maxRedirects) throw FetchException("The page redirected too many times.")
                val next = try {
                    URI(current).resolve(location).toString()
                } catch (e: IllegalArgumentException) {
                    throw FetchException("The page redirected to an address that is not valid.")
                }
                current = guard.check(next)
                continue
            }
            if (response.code !in 200..299) throw FetchException("The page answered with HTTP ${response.code}.")
            return read(current, response)
        }
    }

    private fun read(url: String, response: RawResponse): FetchedPage {
        val contentType = response.header("Content-Type").orEmpty()
        val mime = contentType.substringBefore(';').trim().lowercase().ifEmpty { "text/html" }
        val html = mime == "text/html" || mime == "application/xhtml+xml"
        if (!html && mime != "text/plain") throw FetchException("The page is $mime, not text or HTML.")
        val text = String(response.body, charsetOf(contentType))
        val page = if (html) HtmlExtractor.extract(text, maxChars) else HtmlExtractor.fromPlainText(text, maxChars)
        return FetchedPage(url, page.title, page.text, truncated = response.truncated || page.truncated)
    }

    private fun charsetOf(contentType: String): Charset {
        val name = CHARSET.find(contentType)?.groupValues?.get(1) ?: return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val CHARSET = Regex("charset\\s*=\\s*\"?([A-Za-z0-9_.:-]+)", RegexOption.IGNORE_CASE)
    }
}

/**
 * The real transport, on the platform's `HttpURLConnection`. Redirects are left to the caller, compression is switched off so the
 * byte limit counts real bytes, and the read stops at the limit. It connects by name, so a DNS server that answers differently
 * the second time could still send it to a private address (see [UrlGuard]).
 */
class UrlConnectionTransport(
    private val userAgent: String = "LilyAi-Research/1.0",
    private val timeoutMs: Int = 20_000,
) : HttpTransport {

    override suspend fun get(url: String, maxBytes: Int): RawResponse = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = conn.responseCode
            val headers = conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
            var truncated = false
            val out = ByteArrayOutputStream()
            if (code in 200..299) {
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        val room = maxBytes - out.size()
                        if (n > room) {
                            out.write(buffer, 0, room)
                            truncated = true
                            break
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
            RawResponse(code, headers, out.toByteArray(), truncated)
        } finally {
            conn.disconnect()
        }
    }
}
