package ai.lily.app.research

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class FakeTransport(private val responses: Map<String, RawResponse>) : HttpTransport {
    val requested = ArrayList<String>()

    override suspend fun get(url: String, maxBytes: Int): RawResponse {
        requested += url
        return responses[url] ?: RawResponse(404, emptyMap(), ByteArray(0))
    }
}

class PageFetcherTest {
    private val inside = listOf(10, 0, 0, 5).joinToString(".")

    /** Every host is public except `inside.example`, which resolves to a private address. */
    private val guard = UrlGuard(object : HostResolver {
        override suspend fun resolve(host: String): List<InetAddress> =
            listOf(InetAddress.getByName(if (host == "inside.example") inside else "93.184.216.34"))
    })

    private fun page(body: String, type: String? = "text/html; charset=utf-8", code: Int = 200, truncated: Boolean = false) =
        RawResponse(code, if (type == null) emptyMap() else mapOf("Content-Type" to type), body.toByteArray(), truncated)

    private fun redirect(to: String?, code: Int = 302) =
        RawResponse(code, if (to == null) emptyMap() else mapOf("location" to to), ByteArray(0))

    private fun fetcher(transport: HttpTransport, maxRedirects: Int = 4) =
        GuardedPageFetcher(guard, transport, maxBytes = 1000, maxChars = 1000, maxRedirects = maxRedirects)

    @Test
    fun itFetchesAndExtractsAPage() = runBlocking<Unit> {
        val transport = FakeTransport(mapOf("https://example.com/a" to page("<title>Hi</title><p>Body text</p>")))

        val result = fetcher(transport).fetch("https://Example.com/a#top")

        assertEquals(FetchedPage("https://example.com/a", "Hi", "Body text", truncated = false), result)
    }

    @Test
    fun redirectsAreFollowedAndTheFinalAddressIsReported() = runBlocking<Unit> {
        val transport = FakeTransport(
            mapOf(
                "https://example.com/a" to redirect("/b"),
                "https://example.com/b" to redirect("https://other.example/c", 301),
                "https://other.example/c" to page("<p>Final</p>"),
            ),
        )

        val result = fetcher(transport).fetch("https://example.com/a")

        assertEquals("https://other.example/c", result.url)
        assertEquals("Final", result.text)
        assertEquals(3, transport.requested.size)
    }

    @Test
    fun aRedirectToAPrivateAddressIsRefusedBeforeItIsRequested() = runBlocking<Unit> {
        val transport = FakeTransport(mapOf("https://example.com/a" to redirect("http://inside.example/secret")))

        try {
            fetcher(transport).fetch("https://example.com/a")
            fail("should have been refused")
        } catch (e: UnsafeUrlException) {
            assertTrue(e.message!!.contains("private or reserved"))
        }
        assertEquals(listOf("https://example.com/a"), transport.requested)
    }

    @Test
    fun aFirstAddressThatIsPrivateIsNeverRequested() = runBlocking<Unit> {
        val transport = FakeTransport(emptyMap())

        try {
            fetcher(transport).fetch("http://inside.example/")
            fail("should have been refused")
        } catch (e: UnsafeUrlException) {
            // expected
        }
        assertTrue(transport.requested.isEmpty())
    }

    @Test
    fun tooManyRedirectsStopTheFetch() = runBlocking<Unit> {
        val transport = FakeTransport(mapOf("https://example.com/a" to redirect("/a")))

        try {
            fetcher(transport, maxRedirects = 2).fetch("https://example.com/a")
            fail("should have stopped")
        } catch (e: FetchException) {
            assertTrue(e.message!!.contains("too many"))
        }
        assertEquals(3, transport.requested.size)
    }

    @Test
    fun aRedirectWithoutAPlaceIsAnError() = runBlocking<Unit> {
        val transport = FakeTransport(mapOf("https://example.com/a" to redirect(null)))

        try {
            fetcher(transport).fetch("https://example.com/a")
            fail("should have failed")
        } catch (e: FetchException) {
            assertTrue(e.message!!.contains("without saying where"))
        }
    }

    @Test
    fun httpErrorsAreReported() = runBlocking<Unit> {
        try {
            fetcher(FakeTransport(emptyMap())).fetch("https://example.com/missing")
            fail("should have failed")
        } catch (e: FetchException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun onlyTextAndHtmlAreRead() = runBlocking<Unit> {
        val transport = FakeTransport(
            mapOf(
                "https://example.com/doc" to page("%PDF", "application/pdf"),
                "https://example.com/plain" to page("  just   text ", "text/plain"),
                "https://example.com/bare" to page("<p>no type</p>", null),
            ),
        )
        val f = fetcher(transport)

        try {
            f.fetch("https://example.com/doc")
            fail("a PDF should be refused")
        } catch (e: FetchException) {
            assertTrue(e.message!!.contains("application/pdf"))
        }
        assertEquals("just text", f.fetch("https://example.com/plain").text)
        assertEquals("no type", f.fetch("https://example.com/bare").text)
    }

    @Test
    fun theDeclaredCharsetIsUsedAndAnUnknownOneFallsBackToUtf8() = runBlocking<Unit> {
        val latin = RawResponse(200, mapOf("Content-Type" to "text/html; charset=ISO-8859-1"), "<p>caf\u00e9</p>".toByteArray(Charsets.ISO_8859_1))
        val odd = page("<p>ok</p>", "text/html; charset=no-such-set")
        val transport = FakeTransport(mapOf("https://example.com/l" to latin, "https://example.com/o" to odd))
        val f = fetcher(transport)

        assertEquals("caf\u00e9", f.fetch("https://example.com/l").text)
        assertEquals("ok", f.fetch("https://example.com/o").text)
    }

    @Test
    fun truncationFromEitherStepIsReported() = runBlocking<Unit> {
        val cutByBytes = page("<p>short</p>", truncated = true)
        val cutByChars = page("<p>" + "a".repeat(5000) + "</p>")
        val whole = page("<p>short</p>")
        val transport = FakeTransport(
            mapOf("https://example.com/b" to cutByBytes, "https://example.com/c" to cutByChars, "https://example.com/w" to whole),
        )
        val f = fetcher(transport)

        assertTrue(f.fetch("https://example.com/b").truncated)
        val chars = f.fetch("https://example.com/c")
        assertTrue(chars.truncated)
        assertEquals(1000, chars.text.length)
        assertFalse(f.fetch("https://example.com/w").truncated)
    }
}
