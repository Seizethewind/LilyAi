package ai.lily.app.plugins

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The rules that keep an http plugin on the one host and path the person allowed. Everything here is plain logic, so it
 * is tested without a network.
 */
object HttpGuard {
    private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val PREFIX_CHARS = Regex("^[A-Za-z0-9/._~-]+$")
    private val HEADER_NAME = Regex("^[A-Za-z][A-Za-z0-9-]{0,39}$")
    private val BLOCKED_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".corp", ".arpa", ".onion")

    /** Headers a plugin may not set: the app and the connection decide these. */
    private val BLOCKED_HEADERS = setOf(
        "host", "content-length", "transfer-encoding", "connection", "upgrade", "te", "trailer", "expect",
        "keep-alive", "proxy-authorization", "proxy-connection", "cookie", "set-cookie",
    )

    const val MAX_URL = 1000
    const val MAX_HEADER_VALUE = 1000

    /** A permission is "http:<host>" or "http:<host>/<pinned path>". Returns the permission or the reason it is refused. */
    fun parsePermission(text: String): Result<Permission.Http> {
        val rest = text.removePrefix(Permission.HTTP_PREFIX)
        val host = rest.substringBefore('/')
        val prefix = if (rest.contains('/')) "/" + rest.substringAfter('/') else ""
        hostProblem(host)?.let { return Result.failure(IllegalArgumentException(it)) }
        prefixProblem(prefix)?.let { return Result.failure(IllegalArgumentException(it)) }
        return Result.success(Permission.Http(host, prefix))
    }

    /** One exact lowercase host name: no port, no wildcard, no address, nothing that names the local network. */
    fun hostProblem(host: String): String? = when {
        host.length > 253 || !HOST.matches(host) ->
            "'$host' is not a plain host name (lowercase letters, digits, dots and hyphens; no port, no wildcard)"
        host.substringAfterLast('.').all { it.isDigit() } -> "'$host' looks like an IP address; use the host name"
        BLOCKED_SUFFIXES.any { host.endsWith(it) } -> "'$host' names a local network"
        else -> null
    }

    /** "" or a path that starts with "/" and has no ".", ".." or empty parts. */
    fun prefixProblem(prefix: String): String? {
        if (prefix.isEmpty()) return null
        if (prefix.length > 200 || !PREFIX_CHARS.matches(prefix) || !prefix.startsWith("/")) {
            return "the pinned path '$prefix' may only use letters, digits and / . _ ~ - and must start with /"
        }
        if (prefix.contains("//")) return "the pinned path '$prefix' has an empty part"
        if (prefix.split('/').any { it == "." || it == ".." }) return "the pinned path '$prefix' has a . or .. part"
        return null
    }

    /** True when [path] is inside [prefix]: "/api/v10" allows "/api/v10" and "/api/v10/x" but not "/api/v100". */
    fun pathAllowed(path: String, prefix: String): Boolean =
        prefix.isEmpty() ||
            (path.startsWith(prefix) && (prefix.endsWith("/") || path.length == prefix.length || path[prefix.length] == '/'))

    /**
     * Checks a finished address against one http permission and returns what is wrong with it, or null. It must be
     * https, on exactly the allowed host, inside the pinned path, plain ASCII, with no user, port, fragment or ".." part.
     */
    fun checkUrl(url: String, permission: Permission.Http): String? {
        if (url.length > MAX_URL) return "the address is longer than $MAX_URL characters"
        if (url.any { it.code < 0x21 || it.code > 0x7E }) return "the address has a space, control or non-ASCII character"
        if (!url.startsWith("https://")) return "the address must start with https://"
        if (url.contains('#') || url.contains('\\')) return "the address has a # or a backslash"
        val afterScheme = url.removePrefix("https://")
        val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' }
        val authority = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
        if (authority != permission.host) return "the address is on '$authority', but only ${permission.host} is allowed"
        val rest = if (authorityEnd < 0) "/" else afterScheme.substring(authorityEnd)
        val path = rest.substringBefore('?').ifEmpty { "/" }
        if (!pathAllowed(path, permission.pathPrefix)) return "the path '$path' is outside the allowed ${permission.pathPrefix.ifEmpty { "/" }}"
        if (path.contains("//")) return "the path has an empty part"
        if (path.split('/').any { it == "." || it == ".." }) return "the path has a . or .. part"
        val lower = path.lowercase()
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) return "the path has an encoded dot, slash or backslash"
        return null
    }

    /** Percent-encodes everything but letters, digits and - . _ ~ so a value cannot add a path part, a query or another host. */
    fun encode(value: String): String = buildString {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val plain = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (plain) append(c.toChar()) else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    fun headerNameProblem(name: String): String? = when {
        !HEADER_NAME.matches(name) -> "header name '$name' has characters that are not allowed"
        name.lowercase() in BLOCKED_HEADERS -> "the app sets the $name header itself, a plugin may not"
        else -> null
    }

    /** A header value is one line of printable ASCII. */
    fun headerValueProblem(name: String, value: String): String? = when {
        value.length > MAX_HEADER_VALUE -> "the $name header is longer than $MAX_HEADER_VALUE characters"
        value.any { it.code < 0x20 || it.code > 0x7E } -> "the $name header has a control or non-ASCII character"
        else -> null
    }

    /**
     * Where a redirect may go: an absolute https address or a path on the same host, and only when it passes [checkUrl].
     * Anything else (another host, http, a relative path without a leading slash) returns null and is not followed.
     */
    fun redirectTarget(location: String?, permission: Permission.Http): String? {
        if (location.isNullOrEmpty()) return null
        val absolute = when {
            location.startsWith("https://") -> location
            location.startsWith("/") && !location.startsWith("//") -> "https://${permission.host}$location"
            else -> return null
        }
        return if (checkUrl(absolute, permission) == null) absolute else null
    }

    /** False for loopback, private, link-local, carrier-grade NAT, unique-local, multicast and unspecified addresses. */
    fun isPublicAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        return when (a) {
            is Inet4Address -> {
                val first = b[0].toInt() and 0xFF
                val second = b[1].toInt() and 0xFF
                !(first == 0 || (first == 100 && second in 64..127) || (first == 192 && second == 0 && (b[2].toInt() and 0xFF) == 0) ||
                    (first == 198 && (second == 18 || second == 19)) || first >= 240)
            }
            is Inet6Address -> (b[0].toInt() and 0xFE) != 0xFC
            else -> false
        }
    }
}
