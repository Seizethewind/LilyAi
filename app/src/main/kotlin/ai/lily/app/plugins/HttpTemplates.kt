package ai.lily.app.plugins

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns an [HttpAction] and a tool's arguments into the request that is sent, and into the same request with the
 * secrets hidden, which is what the person is shown. Placeholders are filled in one pass, so a value that looks like a
 * placeholder stays literal. A value in the address is percent-encoded, so it cannot add a path part, a query or a host.
 */
internal object HttpTemplates {
    /** {name} is a tool argument, {secret.name} and {setting.name} are values the person filled in. */
    val TOKEN = Regex("\\{(?:(secret|setting)\\.)?([a-z][a-z0-9_]*)\\}")

    enum class Kind { PARAM, SECRET, SETTING }

    data class Ref(val kind: Kind, val name: String)

    private const val MAX_BODY = 32_000
    private val SKIP = Any()

    fun refs(text: String): List<Ref> = TOKEN.findAll(text).map { Ref(kindOf(it.groupValues[1]), it.groupValues[2]) }.toList()

    private fun kindOf(group: String): Kind = when (group) {
        "secret" -> Kind.SECRET
        "setting" -> Kind.SETTING
        else -> Kind.PARAM
    }

    sealed interface Prepared {
        /** [shown] is what the person approves: secrets hidden. [secretValues] are every secret the plugin holds, for scrubbing the answer. */
        class Ready(val call: HttpCall, val shown: List<Pair<String, String>>, val secretValues: List<String>) : Prepared

        class Problem(val message: String) : Prepared
    }

    fun prepare(action: HttpAction, params: Map<String, String>, settings: List<SettingSpec>, valueOf: (String) -> String?): Prepared {
        val used = LinkedHashSet<Ref>()
        used += refs(action.url)
        action.headers.values.forEach { used += refs(it) }
        action.body?.let { collect(it, used) }
        val missing = ArrayList<String>()
        for (ref in used) {
            if (ref.kind == Kind.PARAM) continue
            val spec = settings.firstOrNull { it.name == ref.name }
                ?: return Prepared.Problem("the plugin uses the setting '${ref.name}' but does not declare it")
            if (spec.required && valueOf(ref.name).isNullOrBlank()) missing += spec.label
        }
        if (missing.isNotEmpty()) {
            return Prepared.Problem("the person has not filled in ${missing.joinToString { "'$it'" }} yet; it is set on the plugin's page")
        }

        fun fill(text: String, shown: Boolean, encode: Boolean): String = TOKEN.replace(text) { m ->
            val name = m.groupValues[2]
            val raw = when (kindOf(m.groupValues[1])) {
                Kind.PARAM -> params[name].orEmpty()
                Kind.SETTING -> valueOf(name).orEmpty()
                Kind.SECRET -> if (shown) return@replace "[secret:$name]" else valueOf(name).orEmpty()
            }
            if (encode) HttpGuard.encode(raw) else raw
        }

        val url = fill(action.url, shown = false, encode = true)
        HttpGuard.checkUrl(url, action.permission)?.let { return Prepared.Problem(it) }
        val headers = LinkedHashMap<String, String>()
        val shownHeaders = ArrayList<Pair<String, String>>()
        for ((name, template) in action.headers) {
            val value = fill(template, shown = false, encode = false)
            HttpGuard.headerValueProblem(name, value)?.let { return Prepared.Problem(it) }
            headers[name] = value
            shownHeaders += "Header $name" to fill(template, shown = true, encode = false)
        }
        var body: String? = null
        var shownBody: String? = null
        if (action.body != null) {
            body = render(toJson(action.body, params, valueOf, settings, shown = false), 0)
            shownBody = render(toJson(action.body, params, valueOf, settings, shown = true), 2)
            if (body.length > MAX_BODY) return Prepared.Problem("the request body is longer than $MAX_BODY characters")
        }

        val details = ArrayList<Pair<String, String>>()
        details += "Method" to action.method.name
        details += "Address" to fill(action.url, shown = true, encode = true)
        details += shownHeaders
        if (shownBody != null) details += "Body" to shownBody
        val secrets = settings.filter { it.secret }.mapNotNull { valueOf(it.name) }.filter { it.isNotBlank() }
        return Prepared.Ready(HttpCall(action.method, url, headers, body, action.timeoutMs, action.permission), details, secrets)
    }

    private fun collect(node: BodyNode, into: MutableSet<Ref>) {
        when (node) {
            is BodyNode.Text -> into += refs(node.value)
            is BodyNode.Obj -> node.fields.values.forEach { collect(it, into) }
            is BodyNode.Arr -> node.items.forEach { collect(it, into) }
            else -> Unit
        }
    }

    /** A string that is exactly one argument the caller left out is dropped, so an optional field is not sent blank. */
    private fun toJson(node: BodyNode, params: Map<String, String>, valueOf: (String) -> String?, settings: List<SettingSpec>, shown: Boolean): Any {
        return when (node) {
            is BodyNode.Num -> node.value
            is BodyNode.Flag -> node.value
            is BodyNode.Text -> {
                val only = TOKEN.matchEntire(node.value)
                if (only != null && kindOf(only.groupValues[1]) == Kind.PARAM && params[only.groupValues[2]].isNullOrBlank()) {
                    SKIP
                } else {
                    TOKEN.replace(node.value) { m ->
                        val name = m.groupValues[2]
                        when (kindOf(m.groupValues[1])) {
                            Kind.PARAM -> params[name].orEmpty()
                            Kind.SETTING -> valueOf(name).orEmpty()
                            Kind.SECRET -> if (shown) "[secret:$name]" else valueOf(name).orEmpty()
                        }
                    }
                }
            }
            is BodyNode.Obj -> JSONObject().also { o ->
                for ((key, child) in node.fields) {
                    val v = toJson(child, params, valueOf, settings, shown)
                    if (v !== SKIP) o.put(key, v)
                }
            }
            is BodyNode.Arr -> JSONArray().also { a ->
                for (child in node.items) {
                    val v = toJson(child, params, valueOf, settings, shown)
                    if (v !== SKIP) a.put(v)
                }
            }
        }
    }

    private fun render(json: Any, indent: Int): String = when (json) {
        is JSONObject -> if (indent > 0) json.toString(indent) else json.toString()
        is JSONArray -> if (indent > 0) json.toString(indent) else json.toString()
        else -> json.toString()
    }

    /** Replaces every secret value (and its percent-encoded form) in [text] with "[secret]". Values under 4 characters are left: they would mangle ordinary text. */
    fun scrub(text: String, secretValues: Collection<String>): String {
        var out = text
        for (secret in secretValues.filter { it.length >= 4 }.distinct().sortedByDescending { it.length }) {
            out = out.replace(secret, "[secret]")
            val encoded = HttpGuard.encode(secret)
            if (encoded != secret) out = out.replace(encoded, "[secret]")
        }
        return out
    }
}
