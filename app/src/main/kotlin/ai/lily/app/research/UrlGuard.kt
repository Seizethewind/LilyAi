package ai.lily.app.research

import ai.lily.app.memory.KnowledgeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException

/** Looks up the addresses of a host name. A seam so tests can run without a network. */
fun interface HostResolver {
    suspend fun resolve(host: String): List<InetAddress>
}

/** The device's own resolver. An IP literal is parsed, not looked up. */
class SystemHostResolver : HostResolver {
    override suspend fun resolve(host: String): List<InetAddress> =
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
}

/** The address must not be fetched. The message says why, in words that can be shown to the person. */
class UnsafeUrlException(message: String) : Exception(message)

/**
 * Decides whether a web address may be fetched by research mode, so a page (or a model) cannot point the app at the phone's own
 * network. An address passes only if all of this holds:
 *
 * - it is http or https, has no username or password, and uses port 80, 443, 8080 or 8443 (or none);
 * - the host is not `localhost`, not a name ending in `.local`, `.internal`, `.lan` and similar, and not a single word;
 * - **every** address the host resolves to is public: not loopback, private (10/8, 172.16/12, 192.168/16), link-local
 *   (169.254/16, which holds cloud metadata services), carrier-grade NAT (100.64/10), multicast, reserved, documentation or
 *   benchmarking ranges, IPv6 unique-local (fc00::/7), or an IPv6 form that wraps an IPv4 address (6to4, Teredo, NAT64,
 *   IPv4-mapped or -compatible).
 *
 * The check runs on the first address and again on every redirect ([GuardedPageFetcher]).
 *
 * **What this does not stop:** the resolver is asked here and the connection is made later by the HTTP stack, which asks again. A
 * hostile DNS server could answer differently the second time (DNS rebinding). Closing that needs a transport that connects to the
 * address that was checked; [HttpTransport] is the seam for it.
 */
class UrlGuard(private val resolver: HostResolver = SystemHostResolver()) {

    /** Returns the normalised address if it is safe to fetch; throws [UnsafeUrlException] if not. */
    suspend fun check(raw: String): String {
        val address = try {
            KnowledgeText.normaliseUrl(raw)
        } catch (e: IllegalArgumentException) {
            throw UnsafeUrlException(e.message ?: "That is not a valid web address.")
        }
        val uri = URI(address)
        val port = uri.port
        if (port != -1 && port !in ALLOWED_PORTS) throw UnsafeUrlException("Port $port is not allowed.")

        val host = uri.host.orEmpty()
        val bare = host.removePrefix("[").removeSuffix("]").trimEnd('.')
        if (bare.isEmpty()) throw UnsafeUrlException("The web address has no host.")
        val isLiteral = host.startsWith("[") || IPV4_LIKE.matches(bare)
        if (!isLiteral) {
            val lower = bare.lowercase()
            if (!lower.contains('.')) throw UnsafeUrlException("$host is not a public web address.")
            if (lower == "localhost" || BLOCKED_SUFFIXES.any { lower.endsWith(it) }) {
                throw UnsafeUrlException("$host is not a public web address.")
            }
        }

        val addresses = try {
            resolver.resolve(bare)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw UnsafeUrlException("Could not look up $host.")
        }
        if (addresses.isEmpty()) throw UnsafeUrlException("Could not look up $host.")
        if (addresses.any { !isPublic(it) }) throw UnsafeUrlException("$host points to a private or reserved address.")
        return address
    }

    companion object {
        private val ALLOWED_PORTS = setOf(80, 443, 8080, 8443)
        private val BLOCKED_SUFFIXES = listOf(
            ".localhost", ".local", ".localdomain", ".internal", ".intranet", ".lan", ".home", ".corp", ".home.arpa",
        )

        /** Digits and dots only, or a single number or hex number: all forms the system resolver may read as an IPv4 address. */
        private val IPV4_LIKE = Regex("^(0[xX][0-9a-fA-F]+|[0-9]+)(\\.(0[xX][0-9a-fA-F]+|[0-9]+)){0,3}$")

        /** True if the address is on the public internet. */
        fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return false
            }
            val b = address.address.map { it.toInt() and 0xff }
            return when (b.size) {
                4 -> !reservedV4(b)
                16 -> !reservedV6(b)
                else -> false
            }
        }

        private fun reservedV4(b: List<Int>): Boolean {
            val (a, c, d) = Triple(b[0], b[1], b[2])
            return a == 0 ||
                a == 10 ||
                a == 127 ||
                (a == 100 && c in 64..127) ||
                (a == 169 && c == 254) ||
                (a == 172 && c in 16..31) ||
                (a == 192 && c == 0 && d == 0) ||
                (a == 192 && c == 0 && d == 2) ||
                (a == 192 && c == 168) ||
                (a == 198 && c in 18..19) ||
                (a == 198 && c == 51 && d == 100) ||
                (a == 203 && c == 0 && d == 113) ||
                a >= 224
        }

        private fun reservedV6(b: List<Int>): Boolean {
            // ::, ::1 and IPv4-compatible forms (first 96 bits zero).
            if (b.subList(0, 12).all { it == 0 }) return true
            // IPv4-mapped (::ffff:a.b.c.d): judge the wrapped IPv4 address.
            if (b.subList(0, 10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff) return reservedV4(b.subList(12, 16))
            // Unique-local fc00::/7.
            if ((b[0] and 0xfe) == 0xfc) return true
            // 6to4 2002::/16 and Teredo 2001:0::/32 wrap IPv4 addresses; documentation 2001:db8::/32.
            if (b[0] == 0x20 && b[1] == 0x02) return true
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00) return true
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) return true
            // NAT64 64:ff9b::/96 wraps an IPv4 address.
            if (b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b) return true
            return false
        }
    }
}
