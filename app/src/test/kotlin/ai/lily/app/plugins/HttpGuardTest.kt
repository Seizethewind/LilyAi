package ai.lily.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class HttpGuardTest {
    private val any = Permission.Http("api.example.com")
    private val pinned = Permission.Http("discord.com", "/api/v10/")

    private fun bad(url: String, p: Permission.Http = any): String = HttpGuard.checkUrl(url, p) ?: throw AssertionError("expected a problem with $url")

    @Test
    fun aPermissionIsOneExactHostWithAnOptionalPinnedPath() {
        assertEquals(Permission.Http("api.github.com", ""), HttpGuard.parsePermission("http:api.github.com").getOrNull())
        assertEquals(Permission.Http("discord.com", "/api/v10/"), HttpGuard.parsePermission("http:discord.com/api/v10/").getOrNull())
        assertEquals("http:discord.com/api/v10/", Permission.Http("discord.com", "/api/v10/").key)
    }

    @Test
    fun hostsThatAreNotPlainPublicNamesAreRefused() {
        for (host in listOf("localhost", "127.0.0.1", "10.0.0.5", "api.example.com:8443", "*.example.com", "API.example.com", "exa mple.com", "printer.local", "db.internal", "-bad.example.com", "")) {
            assertTrue("$host should be refused", HttpGuard.parsePermission("http:$host").isFailure)
        }
    }

    @Test
    fun pinnedPathsAreChecked() {
        assertTrue(HttpGuard.parsePermission("http:discord.com/api/../v10/").isFailure)
        assertTrue(HttpGuard.parsePermission("http:discord.com/api//v10").isFailure)
        assertTrue(HttpGuard.parsePermission("http:discord.com/api/v10?x=1").isFailure)
    }

    @Test
    fun aPinnedPathMeansWholeParts() {
        assertTrue(HttpGuard.pathAllowed("/api/v10", "/api/v10"))
        assertTrue(HttpGuard.pathAllowed("/api/v10/x", "/api/v10"))
        assertFalse(HttpGuard.pathAllowed("/api/v100", "/api/v10"))
        assertTrue(HttpGuard.pathAllowed("/anything", ""))
        assertFalse(HttpGuard.pathAllowed("/api/v10", "/api/v10/"))
    }

    @Test
    fun anAddressMustBeHttpsOnTheExactHostAndPlain() {
        assertNull(HttpGuard.checkUrl("https://api.example.com/repos/a/pulls?x=1", any))
        assertNull(HttpGuard.checkUrl("https://api.example.com", any))
        bad("http://api.example.com/x")
        bad("https://evil.com/x")
        bad("https://api.example.com.evil.com/x")
        bad("https://user@api.example.com/x")
        bad("https://api.example.com@evil.com/x")
        bad("https://api.example.com:8443/x")
        bad("https://api.example.com/x#frag")
        bad("https://api.example.com/a b")
        bad("https://api.example.com/é")
        bad("https://api.example.com/a\\b")
        bad("https://sub.api.example.com/x")
    }

    @Test
    fun theQueryMayCarryEncodedSlashesButThePathMayNot() {
        assertNull(HttpGuard.checkUrl("https://api.example.com/x?ref=feature%2Fone", any))
        bad("https://api.example.com/a%2Fb")
        bad("https://api.example.com/a%2e%2e/b")
        bad("https://api.example.com/a/../b")
        bad("https://api.example.com/a/./b")
        bad("https://api.example.com/a//b")
    }

    @Test
    fun theAddressStaysInsideThePinnedPath() {
        assertNull(HttpGuard.checkUrl("https://discord.com/api/v10/channels/1/messages", pinned))
        bad("https://discord.com/api/v9/channels/1", pinned)
        bad("https://discord.com/", pinned)
        bad("https://discord.com?x=1", pinned)
        bad("https://discord.com/api/v10/../v9/x", pinned)
    }

    @Test
    fun aValueIsEncodedSoItCannotAddAPartAQueryOrAHost() {
        assertEquals("a%20b%2Fc%3Fd%23e%40f", HttpGuard.encode("a b/c?d#e@f"))
        assertEquals("AZaz09-._~", HttpGuard.encode("AZaz09-._~"))
        assertEquals("%C3%A9", HttpGuard.encode("é"))
        assertEquals("%25", HttpGuard.encode("%"))
    }

    @Test
    fun headersAreOneLineAndTheAppKeepsTheConnectionOnes() {
        assertNull(HttpGuard.headerNameProblem("Authorization"))
        assertNull(HttpGuard.headerNameProblem("X-GitHub-Api-Version"))
        for (name in listOf("Host", "content-length", "Transfer-Encoding", "Connection", "Cookie", "Proxy-Authorization", "bad name", "")) {
            assertNotNull("$name", HttpGuard.headerNameProblem(name))
        }
        assertNull(HttpGuard.headerValueProblem("A", "Bearer abc"))
        assertNotNull(HttpGuard.headerValueProblem("A", "x\r\nHost: evil"))
        assertNotNull(HttpGuard.headerValueProblem("A", "café"))
        assertNotNull(HttpGuard.headerValueProblem("A", "x".repeat(1001)))
    }

    @Test
    fun aRedirectMayOnlyStayOnTheHostAndPath() {
        assertEquals("https://api.example.com/b", HttpGuard.redirectTarget("https://api.example.com/b", any))
        assertEquals("https://api.example.com/b", HttpGuard.redirectTarget("/b", any))
        assertNull(HttpGuard.redirectTarget("https://evil.com/b", any))
        assertNull(HttpGuard.redirectTarget("//evil.com/b", any))
        assertNull(HttpGuard.redirectTarget("http://api.example.com/b", any))
        assertNull(HttpGuard.redirectTarget("b", any))
        assertNull(HttpGuard.redirectTarget(null, any))
        assertNull(HttpGuard.redirectTarget("/other", pinned))
        assertEquals("https://discord.com/api/v10/x", HttpGuard.redirectTarget("/api/v10/x", pinned))
    }

    private fun ip(vararg b: Int): InetAddress = InetAddress.getByAddress(ByteArray(b.size) { b[it].toByte() })

    @Test
    fun privateAndLocalAddressesAreNotPublic() {
        assertTrue(HttpGuard.isPublicAddress(ip(140, 82, 112, 6)))
        assertTrue(HttpGuard.isPublicAddress(ip(0x26, 0x06, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)))
        for (a in listOf(ip(127, 0, 0, 1), ip(10, 1, 2, 3), ip(192, 168, 1, 1), ip(172, 16, 0, 1), ip(169, 254, 169, 254), ip(0, 0, 0, 0), ip(100, 64, 0, 1), ip(224, 0, 0, 1), ip(198, 18, 0, 1))) {
            assertFalse("${a.hostAddress}", HttpGuard.isPublicAddress(a))
        }
        assertFalse(HttpGuard.isPublicAddress(ip(0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
        assertFalse(HttpGuard.isPublicAddress(ip(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    }

    @Test
    fun scrubRemovesASecretInItsPlainAndEncodedForm() {
        val out = HttpTemplates.scrub("a tok_abc+123 b tok_abc%2B123 c", listOf("tok_abc+123"))
        assertEquals("a [secret] b [secret] c", out)
        assertEquals("ab abc", HttpTemplates.scrub("ab abc", listOf("ab")))
    }
}
