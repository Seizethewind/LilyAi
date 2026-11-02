package ai.lily.app.research

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class UrlGuardTest {
    /** Host name to the address literals it "resolves" to. Literals in a URL are parsed, never looked up. */
    private val table = HashMap<String, List<String>>()

    private val resolver = object : HostResolver {
        override suspend fun resolve(host: String): List<InetAddress> {
            if (host.contains(':') || host.matches(Regex("[0-9.]+"))) return listOf(InetAddress.getByName(host))
            val literals = table[host] ?: throw UnknownHostException(host)
            return literals.map { InetAddress.getByName(it) }
        }
    }
    private val guard = UrlGuard(resolver)

    private suspend fun blockedMessage(url: String): String = try {
        guard.check(url)
        fail("should have been blocked: $url")
        ""
    } catch (e: UnsafeUrlException) {
        e.message.orEmpty()
    }

    @Test
    fun aPublicHostIsAllowedAndTheAddressIsNormalised() = runBlocking<Unit> {
        table["example.com"] = listOf("93.184.216.34")

        assertEquals("https://example.com/a?b=1", guard.check("HTTPS://Example.com/a?b=1#frag"))
    }

    @Test
    fun onlyOrdinaryWebPortsPass() = runBlocking<Unit> {
        table["example.com"] = listOf("93.184.216.34")

        assertTrue(blockedMessage("http://example.com:22/").contains("Port 22"))
        assertEquals("http://example.com:8080/x", guard.check("http://example.com:8080/x"))
        assertEquals("https://example.com:8443", guard.check("https://example.com:8443/"))
    }

    @Test
    fun aHostThatCannotBeLookedUpIsBlocked() = runBlocking<Unit> {
        assertTrue(blockedMessage("https://nowhere.example.com/").contains("Could not look up"))
    }
}
