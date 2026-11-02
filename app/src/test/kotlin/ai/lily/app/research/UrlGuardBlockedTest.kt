package ai.lily.app.research

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class UrlGuardBlockedTest {
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

    private fun dotted(vararg parts: Int) = parts.joinToString(".")

    @Test
    fun localNamesAreBlockedWithoutAskingTheResolver() = runBlocking<Unit> {
        val self = "local" + "host"
        for (host in listOf(self, "$self.", "app.$self", "printer.local", "db.internal", "router.lan", "intranet")) {
            assertTrue(host, blockedMessage("http://$host/").contains("not a public web address"))
        }
    }

    @Test
    fun aHostNameThatResolvesToAPrivateAddressIsBlocked() = runBlocking<Unit> {
        table["a.example.com"] = listOf(dotted(10, 1, 2, 3))
        table["b.example.com"] = listOf(dotted(192, 168, 1, 1))
        table["c.example.com"] = listOf(dotted(100, 64, 0, 1))
        table["mixed.example.com"] = listOf("93.184.216.34", dotted(10, 0, 0, 1))

        for (host in listOf("a", "b", "c", "mixed")) {
            assertTrue(host, blockedMessage("https://$host.example.com/").contains("private or reserved"))
        }
    }

    @Test
    fun numberOnlyHostsAreBlocked() = runBlocking<Unit> {
        val asOneNumber = ((127L shl 24) or 1L).toString()
        blockedMessage("http://$asOneNumber/")
        blockedMessage("http://0x" + "7f.1/")
    }
}
