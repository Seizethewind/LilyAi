package ai.lily.app.research

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class UrlGuardSchemeTest {
    private val resolver = object : HostResolver {
        override suspend fun resolve(host: String): List<InetAddress> {
            if (host.contains(':') || host.matches(Regex("[0-9.]+"))) return listOf(InetAddress.getByName(host))
            if (host == "example.com") return listOf(InetAddress.getByName("93.184.216.34"))
            throw UnknownHostException(host)
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

    private fun dotted(vararg parts: Int) = parts.joinToString(".")

    @Test
    fun addressLiteralsInThePrivateWorldAreBlocked() = runBlocking<Unit> {
        blockedMessage("http://" + dotted(127, 0, 0, 1) + "/")
        blockedMessage("http://" + dotted(127, 1, 2, 3) + ":8080/page")
        blockedMessage("http://[" + "::" + "1]/")
        blockedMessage("http://" + dotted(10, 0, 0, 5) + "/")
        blockedMessage("http://" + dotted(169, 254, 1, 1) + "/")
    }

    @Test
    fun otherSchemesAndLoginDetailsAreBlocked() = runBlocking<Unit> {
        val withLogin = "https://" + "someone" + ":" + "word" + "@" + "example.com/"

        blockedMessage("ftp://example.com/")
        blockedMessage("file:///tmp/notes")
        blockedMessage(withLogin)
        blockedMessage("not a url")
        assertTrue(blockedMessage("").isNotEmpty())
    }
}
