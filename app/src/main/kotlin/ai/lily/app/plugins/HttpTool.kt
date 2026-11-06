package ai.lily.app.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlin.coroutines.cancellation.CancellationException

internal object HttpPlugin {
    const val KEY_PREFIX = "http:"
    const val KIND = "http"
    const val MAX_RESPONSE = 16_000

    // shortcut: no PATCH (HttpURLConnection cannot send it) and DELETE has no body; swap the transport when a plugin needs them.
    val METHODS = listOf("GET", "POST", "PUT", "DELETE")
}

/** "Send web requests to this host", optionally only under one path. Written http:host or http:host/path/. */
data class HttpPermission(val host: String, val pathPrefix: String) : Permission {
    override val key: String get() = HttpPlugin.KEY_PREFIX + host + pathPrefix
}

/** A value the person types in once (a token, say). The manifest only names it; the value never lives in a manifest. */
data class PluginSecret(val name: String, val label: String)

/**
 * One web request a tool makes. [url], the header values and [body] may hold {param} and {secret.name} placeholders.
 * [body] is JSON text ("" means none). [permissionKey] is the http permission that covers this request.
 */
data class HttpAction(
    val method: String,
    val url: String,
    val host: String,
    val pathPrefix: String,
    val headers: Map<String, String>,
    val body: String,
    val timeoutMs: Long,
    val permissionKey: String,
)

data class HttpToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val action: HttpAction,
)

/** Reads the http parts of a manifest. Strict like the rest of the parser: anything unexpected is an error. */
internal object HttpManifest {
    private const val MAX_SECRETS = 5
    private const val MAX_HEADERS = 10
    private const val MAX_URL = 1000
    private const val MAX_HEADER_VALUE = 1000
    private const val MAX_BODY = 4000
    private const val DEFAULT_TIMEOUT_MS = 15_000L
    private const val MIN_TIMEOUT_MS = 1_000L
    private const val MAX_TIMEOUT_MS = 60_000L

    private val HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val PREFIX = Regex("^/[A-Za-z0-9._~/-]*$")
    private val URL_TAIL = Regex("^[A-Za-z0-9._~:/?&=%+,;@!*'()\\-{}]*$")
    private val HEADER_NAME = Regex("^[A-Za-z][A-Za-z0-9-]{0,39}$")
    private val SECRET_NAME = Regex("^[a-z][a-z0-9_]{0,29}$")
    private val BANNED_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "upgrade", "cookie")
    private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".internal", ".lan", ".home", ".corp")

    private class Target(val host: String, val path: String)

    // shortcut: a name that resolves to a private address (DNS rebinding) is not caught; resolve and check the address if that matters.
    private fun hostProblem(host: String): String? = when {
        host.length > 253 || !HOST.matches(host) -> "'$host' is not a plain web name like api.example.com (no port, no login, no IP address)"
        host.substringAfterLast('.').all { it.isDigit() } -> "'$host' is an IP address; use a name"
        LOCAL_SUFFIXES.any { host.endsWith(it) } -> "'$host' looks like a local address"
        else -> null
    }

    private fun prefixProblem(prefix: String): String? = when {
        prefix.isEmpty() -> null
        !PREFIX.matches(prefix) -> "the path '$prefix' has characters that are not allowed"
        prefix.contains("//") || prefix.split('/').any { it == "." || it == ".." } -> "the path '$prefix' must not contain '.', '..' or '//'"
        else -> null
    }

    fun permission(text: String, label: String, errors: MutableList<String>): HttpPermission? {
        val rest = text.removePrefix(HttpPlugin.KEY_PREFIX)
        val host = rest.substringBefore('/')
        val prefix = if (rest.contains('/')) "/" + rest.substringAfter('/') else ""
        val problem = hostProblem(host) ?: prefixProblem(prefix)
        if (problem != null) {
            errors += "$label: $problem"
            return null
        }
        return HttpPermission(host, prefix)
    }

    fun secrets(root: JSONObject, errors: MutableList<String>): List<PluginSecret> {
        val v = root.opt("secrets")
        if (v == null || v === JSONObject.NULL) return emptyList()
        if (v !is JSONArray) {
            errors += "secrets must be a list"
            return emptyList()
        }
        if (v.length() > MAX_SECRETS) {
            errors += "secrets has more than $MAX_SECRETS entries"
            return emptyList()
        }
        val out = ArrayList<PluginSecret>()
        for (i in 0 until v.length()) {
            val o = v.opt(i) as? JSONObject
            if (o == null) {
                errors += "secrets[$i] must be an object"
                continue
            }
            val name = o.opt("name") as? String
            val label = o.opt("label") as? String
            if (name == null || !SECRET_NAME.matches(name)) {
                errors += "secrets[$i]: name must be lowercase letters, digits and underscores, starting with a letter"
                continue
            }
            if (label == null || label.length !in 1..60 || Visible.hasHidden(label)) {
                errors += "secret '$name': label must be 1 to 60 characters without hidden characters"
                continue
            }
            if (out.any { it.name == name }) {
                errors += "secret '$name' is listed twice"
                continue
            }
            out += PluginSecret(name, label)
        }
        return out
    }

    fun action(
        a: JSONObject,
        label: String,
        paramNames: Set<String>,
        permissions: List<HttpPermission>,
        secretNames: Set<String>,
        errors: MutableList<String>,
    ): HttpAction? {
        val before = errors.size
        val where = "$label: action"

        val methodValue = a.opt("method")
        val method = when {
            methodValue == null || methodValue === JSONObject.NULL -> "GET"
            methodValue is String -> methodValue
            else -> ""
        }
        if (method !in HttpPlugin.METHODS) errors += "$where.method must be one of ${HttpPlugin.METHODS.joinToString()}"

        val urlText = a.opt("url") as? String
        val target = if (urlText == null) {
            errors += "$where.url is required"
            null
        } else {
            target(urlText, where, errors)
        }
        if (urlText != null) tokens(urlText, "$where.url", paramNames, secretNames, errors)

        val permission = target?.let { t ->
            permissions.firstOrNull { it.host == t.host && t.path.ifEmpty { "/" }.startsWith(it.pathPrefix) }
        }
        if (target != null && permission == null) {
            errors += "$where: it calls ${target.host}${target.path} but the plugin does not ask for ${HttpPlugin.KEY_PREFIX}${target.host} (with a path that covers it)"
        }

        val headers = LinkedHashMap<String, String>()
        val headerValue = a.opt("headers")
        if (headerValue != null && headerValue !== JSONObject.NULL) {
            if (headerValue !is JSONObject) {
                errors += "$where.headers must be an object"
            } else if (headerValue.length() > MAX_HEADERS) {
                errors += "$where.headers has more than $MAX_HEADERS entries"
            } else {
                for (key in headerValue.keys()) {
                    val value = headerValue.opt(key) as? String
                    val lower = key.lowercase()
                    when {
                        !HEADER_NAME.matches(key) -> errors += "$where: header name '$key' has characters that are not allowed"
                        lower in BANNED_HEADERS || lower.startsWith("proxy-") -> errors += "$where: header '$key' cannot be set by a plugin"
                        headers.keys.any { it.equals(key, ignoreCase = true) } -> errors += "$where: header '$key' is listed twice"
                        value == null || value.length > MAX_HEADER_VALUE || Visible.hasHidden(value) ->
                            errors += "$where: header '$key' must be text of at most $MAX_HEADER_VALUE characters without hidden characters"
                        else -> {
                            tokens(value, "$where: header '$key'", paramNames, secretNames, errors)
                            headers[key] = value
                        }
                    }
                }
            }
        }

        val bodyValue = a.opt("body")
        val body = when {
            bodyValue == null || bodyValue === JSONObject.NULL -> ""
            bodyValue is JSONObject -> bodyValue.toString()
            else -> {
                errors += "$where.body must be a JSON object"
                ""
            }
        }
        if (body.length > MAX_BODY) errors += "$where.body is longer than $MAX_BODY characters"
        if (body.isNotEmpty()) {
            if (method != "POST" && method != "PUT") errors += "$where: only POST and PUT can have a body"
            tokens(body, "$where.body", paramNames, secretNames, errors)
        }

        val timeoutValue = a.opt("timeoutMs")
        val timeout = when (timeoutValue) {
            null -> DEFAULT_TIMEOUT_MS
            is Int -> timeoutValue.toLong()
            is Long -> timeoutValue
            else -> {
                errors += "$where.timeoutMs must be a whole number"
                DEFAULT_TIMEOUT_MS
            }
        }
        if (timeout !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) errors += "$where.timeoutMs must be $MIN_TIMEOUT_MS to $MAX_TIMEOUT_MS"

        if (errors.size > before || urlText == null || target == null || permission == null) return null
        return HttpAction(method, urlText, target.host, permission.pathPrefix, headers, body, timeout, permission.key)
    }

    private fun target(url: String, where: String, errors: MutableList<String>): Target? {
        if (url.length > MAX_URL) {
            errors += "$where.url is longer than $MAX_URL characters"
            return null
        }
        if (!url.startsWith("https://")) {
            errors += "$where.url must start with https://"
            return null
        }
        val rest = url.removePrefix("https://")
        val host = rest.takeWhile { it != '/' && it != '?' }
        val problem = hostProblem(host)
        if (problem != null) {
            errors += "$where.url: $problem"
            return null
        }
        val tail = rest.drop(host.length)
        if (!URL_TAIL.matches(tail)) {
            errors += "$where.url has characters that are not allowed"
            return null
        }
        val path = tail.substringBefore('?')
        if (path.split('/').any { it == "." || it == ".." }) {
            errors += "$where.url must not contain '.' or '..' path parts"
            return null
        }
        return Target(host, path)
    }

    private fun tokens(text: String, where: String, paramNames: Set<String>, secretNames: Set<String>, errors: MutableList<String>) {
        for (m in HttpText.TOKEN.findAll(text)) {
            val name = m.groupValues[2]
            if (m.groupValues[1].isNotEmpty()) {
                if (name !in secretNames) errors += "$where uses {secret.$name} but the plugin declares no secret with that name"
            } else if (name !in paramNames) {
                errors += "$where uses {$name} but the tool has no param with that name"
            }
        }
    }
}

/** Filling placeholders, one pass, with the escaping each place needs so a value can never change the shape of the request. */
internal object HttpText {
    /** {name} is a param, {secret.name} a secret. */
    val TOKEN = Regex("\\{(secret\\.)?([a-z][a-z0-9_]*)\\}")

    fun render(text: String, values: Map<String, String>, escape: (String) -> String, secret: (String, (String) -> String) -> String): String =
        TOKEN.replace(text) { m ->
            val name = m.groupValues[2]
            if (m.groupValues[1].isNotEmpty()) secret(name, escape) else escape(values[name].orEmpty())
        }

    fun secretNames(text: String): Set<String> =
        TOKEN.findAll(text).filter { it.groupValues[1].isNotEmpty() }.map { it.groupValues[2] }.toSet()

    /** Everything except letters, digits and - . _ ~ becomes %XX, so a value stays inside its own path part or query value. */
    fun percent(v: String): String = buildString {
        for (b in v.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                append(c)
            } else {
                append('%').append("%02X".format(c.code))
            }
        }
    }

    fun urlPart(v: String): String {
        require(v != "." && v != "..") { "a value of '.' or '..' cannot go into a web address" }
        return percent(v)
    }

    fun headerPart(v: String): String {
        require(!Visible.hasHidden(v)) { "a value for a header has a line break, control or hidden character" }
        return v
    }

    /** The text as it goes inside a JSON string (no surrounding quotes). */
    fun jsonPart(v: String): String = JSONObject.quote(v).let { it.substring(1, it.length - 1) }
}

/** The last check before anything is sent: the final address must still be https, on the allowed host and inside the allowed path. */
internal object HttpGuard {
    fun problem(action: HttpAction, url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return "the web address is not valid"
        }
        if (uri.scheme != "https") return "only https addresses are allowed"
        if (uri.rawUserInfo != null || uri.rawFragment != null) return "the web address has a login or fragment, which is not allowed"
        if (uri.port != -1 && uri.port != 443) return "only the standard https port is allowed"
        if (uri.host?.lowercase() != action.host) return "the web address points at ${uri.host}, not ${action.host}"
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        if (!path.startsWith(action.pathPrefix)) return "the web address is outside the allowed path ${action.pathPrefix}"
        if (path.split('/').any { it == "." || it == ".." }) return "the web address has '.' or '..' path parts"
        return null
    }
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutMs: Long,
)

/** [cut] is true when the answer was longer than the limit and only the start was kept. */
data class HttpResponse(val status: Int, val body: String, val cut: Boolean)

/** How the app sends one request. The real one is [UrlConnectionTransport]; tests use a fake. */
interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Plain HttpURLConnection. Redirects are never followed and the answer is read only up to the limit. */
class UrlConnectionTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val c = URL(request.url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = request.timeoutMs.toInt()
            c.readTimeout = request.timeoutMs.toInt()
            c.requestMethod = request.method
            c.setRequestProperty("User-Agent", "Lily")
            for ((k, v) in request.headers) c.setRequestProperty(k, v)
            if (request.body != null) {
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val status = c.responseCode
            if (status in 300..399) HttpResponse(status, "", false) else read(c, status, request.timeoutMs)
        } finally {
            c.disconnect()
        }
    }

    private fun read(c: HttpURLConnection, status: Int, timeoutMs: Long): HttpResponse {
        val stream = (if (status >= 400) c.errorStream else c.inputStream) ?: return HttpResponse(status, "", false)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        stream.use { s ->
            while (out.size() <= HttpPlugin.MAX_RESPONSE) {
                if (System.nanoTime() > deadline) throw IOException("the answer took too long")
                val n = s.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        val bytes = out.toByteArray()
        val cut = bytes.size > HttpPlugin.MAX_RESPONSE
        return HttpResponse(status, String(bytes, 0, minOf(bytes.size, HttpPlugin.MAX_RESPONSE), Charsets.UTF_8), cut)
    }
}

/**
 * Turns one http tool call into a request and the request's answer into text for the worker.
 * [prepare] builds two views of the same request: the one that is sent (secrets filled in) and the one the person
 * is shown (secrets left as {secret.name}), so a secret can never reach the approval screen.
 */
class HttpRunner(private val transport: HttpTransport, private val secrets: PluginSecrets) {
    sealed interface Prepared {
        class Ready(val view: List<Pair<String, String>>, val request: HttpRequest, val secretValues: List<String>) : Prepared
        class Bad(val message: String) : Prepared
    }

    fun prepare(pluginId: String, action: HttpAction, values: Map<String, String>): Prepared {
        val used = LinkedHashSet<String>()
        for (text in listOf(action.url, action.body) + action.headers.values) used += HttpText.secretNames(text)
        val real = LinkedHashMap<String, String>()
        for (name in used) {
            val value = secrets.get(pluginId, name)
            if (value.isNullOrEmpty()) return Prepared.Bad("the secret '$name' has no value yet; the person must enter it on the plugin's page")
            real[name] = value
        }
        try {
            val send = parts(action, values) { name, escape -> escape(real.getValue(name)) }
            val problem = HttpGuard.problem(action, send.url)
            if (problem != null) return Prepared.Bad(problem)
            val shown = parts(action, values) { name, _ -> "{secret.$name}" }
            val view = ArrayList<Pair<String, String>>()
            view += "method" to action.method
            view += "url" to clip(Visible.escape(shown.url))
            for ((k, v) in shown.headers) view += "header $k" to clip(Visible.escape(v))
            if (shown.body.isNotEmpty()) view += "body" to clip(Visible.escape(shown.body))
            val request = HttpRequest(action.method, send.url, send.headers, send.body.ifEmpty { null }, action.timeoutMs)
            return Prepared.Ready(view, request, real.values.toList())
        } catch (e: IllegalArgumentException) {
            return Prepared.Bad(e.message ?: "a value cannot be used in the request")
        }
    }

    /** Never throws. Anything that looks like one of the secrets is removed from the text before it is returned. */
    suspend fun send(request: HttpRequest, secretValues: List<String>): String {
        val text = try {
            format(transport.send(request))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "error: the request failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return scrub(text, secretValues)
    }

    private class Parts(val url: String, val headers: Map<String, String>, val body: String)

    private fun parts(action: HttpAction, values: Map<String, String>, secret: (String, (String) -> String) -> String): Parts {
        val url = HttpText.render(action.url, values, { HttpText.urlPart(it) }, secret)
        val headers = LinkedHashMap<String, String>()
        for ((k, v) in action.headers) headers[k] = HttpText.render(v, values, { HttpText.headerPart(it) }, secret)
        val body = HttpText.render(action.body, values, { HttpText.jsonPart(it) }, secret)
        if (body.isNotEmpty() && headers.keys.none { it.equals("content-type", ignoreCase = true) }) {
            headers["Content-Type"] = "application/json; charset=utf-8"
        }
        return Parts(url, headers, body)
    }

    private fun format(r: HttpResponse): String {
        val body = r.body + if (r.cut) "\n[cut: the answer is longer than ${HttpPlugin.MAX_RESPONSE} bytes]" else ""
        return when {
            r.status in 300..399 -> "error: the server answered with a redirect (status ${r.status}); redirects are never followed"
            r.status >= 400 -> "error: HTTP ${r.status}\n$body"
            else -> "status=${r.status}\n$body"
        }
    }

    private fun scrub(text: String, secretValues: List<String>): String {
        var out = text
        for (secret in secretValues) {
            for (form in listOf(secret, HttpText.percent(secret), HttpText.jsonPart(secret))) {
                if (form.isNotEmpty()) out = out.replace(form, "[secret]")
            }
        }
        return out
    }

    private fun clip(text: String): String =
        if (text.length <= MAX_SHOWN) text else text.take(MAX_SHOWN) + "... (${text.length - MAX_SHOWN} more characters)"

    private companion object {
        const val MAX_SHOWN = 2_000
    }
}
