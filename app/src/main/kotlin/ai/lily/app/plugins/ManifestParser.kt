package ai.lily.app.plugins

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

sealed interface ParseResult {
    data class Ok(val manifest: PluginManifest) : ParseResult
    data class Invalid(val errors: List<String>) : ParseResult
}

/**
 * Reads and checks a plugin manifest. It is strict on purpose: anything it does not understand is an error, and every
 * limit below keeps a manifest small enough for a person to read before allowing it.
 */
object ManifestParser {
    const val MAX_BYTES = 64 * 1024
    const val SCHEMA = 1
    const val OWN_PACKAGE = "ai.lily.app"
    const val KIND_ANDROID_SERVICE = "android-service"

    /** {name} inside text. Names are lowercase; an unknown name is an error, never passed through. */
    val PLACEHOLDER = Regex("\\{([a-z][a-z0-9_]*)\\}")
    val PROMPT_PLACEHOLDERS = setOf("selection", "file", "language")

    /** The optional tile colour of a plugin card: #RRGGBB. */
    val ACCENT = Regex("^#[0-9A-Fa-f]{6}$")

    private const val MAX_PERMISSIONS = 10
    private const val MAX_TOOLS = 20
    private const val MAX_PARAMS = 8
    private const val MAX_EXTRAS = 20
    private const val MAX_LIST = 20
    private const val MAX_EXTRA_TEXT = 4000
    private const val MAX_PARAM_LENGTH = 8000
    private const val DEFAULT_PARAM_LENGTH = 1000
    private const val MAX_COMMANDS = 30
    private const val MAX_SNIPPETS = 100
    private const val MAX_TEMPLATE = 4000
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MIN_TIMEOUT_MS = 1_000L
    private const val MAX_TIMEOUT_MS = 300_000L
    private const val MAX_ERRORS = 20

    private val ID = Regex("^[a-z0-9][a-z0-9.-]{2,63}$")
    private val VERSION = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}$")
    private val TOOL_NAME = Regex("^[a-z][a-z0-9_]{1,39}$")
    private val PARAM_NAME = Regex("^[a-z][a-z0-9_]{0,29}$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val CLASS_NAME = Regex("^[A-Za-z][A-Za-z0-9_.$]{0,199}$")
    private val INTENT_ACTION = Regex("^[A-Za-z][A-Za-z0-9_.]{0,199}$")
    private val EXTRA_KEY = Regex("^[A-Za-z][A-Za-z0-9_.]{0,119}$")
    private val COMMAND_NAME = Regex("^[a-z][a-z0-9-]{1,39}$")
    private val SNIPPET_PREFIX = Regex("^[A-Za-z0-9_.-]{1,30}$")

    fun isValidId(id: String): Boolean = ID.matches(id)

    /** One emoji or one or two letters for the tile. Whitespace and invisible characters (a zero-width joiner too) are refused. */
    fun isValidIcon(icon: String): Boolean =
        icon.isNotBlank() && icon.codePointCount(0, icon.length) <= 2 && icon.none { it.isWhitespace() || Visible.isHidden(it) }

    fun parse(text: String): ParseResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return ParseResult.Invalid(listOf("the manifest is larger than ${MAX_BYTES / 1024} KB"))
        }
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return ParseResult.Invalid(listOf("the manifest is not valid JSON: ${e.message}"))
        }
        val errors = mutableListOf<String>()

        if (root.opt("schema") != SCHEMA) errors += "schema must be $SCHEMA"

        val id = root.opt("id") as? String
        if (id == null || !ID.matches(id)) {
            errors += "id must be 3 to 64 characters: lowercase letters, digits, dots and hyphens, starting with a letter or digit"
        }
        val name = shown(root, "name", 1, 60, "plugin", errors)
        val version = root.opt("version") as? String
        if (version == null || !VERSION.matches(version)) errors += "version must look like 1.2.3"
        val description = shown(root, "description", 0, 300, "plugin", errors)
        val author = shown(root, "author", 0, 60, "plugin", errors)
        val icon = optionalText(root, "icon", "must be one emoji or one or two letters", errors) { isValidIcon(it) }
        val accent = optionalText(root, "accent", "must look like #1A2B3C", errors) { ACCENT.matches(it) }

        val permissions = LinkedHashSet<Permission>()
        val permissionList = root.opt("permissions")
        if (permissionList != null && permissionList !== JSONObject.NULL) {
            if (permissionList !is JSONArray) {
                errors += "permissions must be a list"
            } else if (permissionList.length() > MAX_PERMISSIONS) {
                errors += "permissions has more than $MAX_PERMISSIONS entries"
            } else {
                for (i in 0 until permissionList.length()) {
                    val p = permissionList.opt(i) as? String
                    if (p != null && p.startsWith(HttpPlugin.KEY_PREFIX)) {
                        HttpManifest.permission(p, "permissions[$i]", errors)?.let { permissions += it }
                        continue
                    }
                    if (p == null || !p.startsWith(Permission.EXTERNAL_APP_PREFIX)) {
                        errors += "permissions[$i] is not a permission this version knows"
                        continue
                    }
                    val pkg = p.removePrefix(Permission.EXTERNAL_APP_PREFIX)
                    val problem = packageProblem(pkg)
                    if (problem != null) {
                        errors += "permissions[$i]: $problem"
                        continue
                    }
                    permissions += Permission.ExternalApp(pkg)
                }
            }
        }
        val allowedPackages = permissions.filterIsInstance<Permission.ExternalApp>().map { it.packageName }.toSet()
        val httpPermissions = permissions.filterIsInstance<HttpPermission>()
        val secrets = HttpManifest.secrets(root, errors)
        val secretNames = secrets.map { it.name }.toSet()

        val tools = ArrayList<PluginToolSpec>()
        val httpTools = ArrayList<HttpToolSpec>()
        for ((i, o) in objects(root, "tools", MAX_TOOLS, "", errors)) {
            when (val tool = parseTool(o, i, allowedPackages, httpPermissions, secretNames, errors)) {
                is PluginToolSpec -> tools += tool
                is HttpToolSpec -> httpTools += tool
            }
        }
        for ((toolName, same) in (tools.map { it.name } + httpTools.map { it.name }).groupBy { it }) {
            if (same.size > 1) errors += "tool '$toolName' is listed twice"
        }

        val commands = ArrayList<PromptCommand>()
        for ((i, o) in objects(root, "promptCommands", MAX_COMMANDS, "", errors)) {
            parseCommand(o, i, errors)?.let { commands += it }
        }
        for ((commandName, same) in commands.groupBy { it.name }) {
            if (same.size > 1) errors += "prompt command '$commandName' is listed twice"
        }

        val snippets = ArrayList<Snippet>()
        for ((i, o) in objects(root, "snippets", MAX_SNIPPETS, "", errors)) {
            parseSnippet(o, i, errors)?.let { snippets += it }
        }
        for ((prefix, same) in snippets.groupBy { it.prefix }) {
            if (same.size > 1) errors += "snippet '$prefix' is listed twice"
        }

        if (errors.isNotEmpty() || id == null || version == null) {
            return ParseResult.Invalid(errors.take(MAX_ERRORS))
        }
        return ParseResult.Ok(
            PluginManifest(
                id, name, version, description, permissions.toList(), tools, commands, snippets, author, icon, accent,
                httpTools, secrets,
            ),
        )
    }

    private fun packageProblem(pkg: String): String? = when {
        !PACKAGE.matches(pkg) -> "'$pkg' is not a valid app package name"
        pkg == OWN_PACKAGE || pkg.startsWith("$OWN_PACKAGE.") -> "plugins cannot target Lily itself"
        pkg.startsWith("android.") || pkg.startsWith("com.android.") -> "plugins cannot target Android system packages"
        else -> null
    }

    /** One-line text the person will read: bounded, and without hidden or direction-changing characters. */
    private fun shown(o: JSONObject, key: String, min: Int, max: Int, label: String, errors: MutableList<String>): String {
        val v = o.opt(key) as? String
        if (v == null) {
            if (min > 0) errors += "$label: $key is required"
            return ""
        }
        if (v.length < min || v.length > max) {
            errors += "$label: $key must be $min to $max characters"
            return v.take(max)
        }
        if (Visible.hasHidden(v)) errors += "$label: $key contains control or invisible characters"
        return v
    }

    /** An optional text value: absent is "", present but wrong is an error. */
    private fun optionalText(o: JSONObject, key: String, rule: String, errors: MutableList<String>, valid: (String) -> Boolean): String {
        val v = o.opt(key)
        if (v == null || v === JSONObject.NULL) return ""
        if (v !is String || !valid(v)) {
            errors += "plugin: $key $rule"
            return ""
        }
        return v
    }

    /** Index and object for each entry of an optional list of objects. */
    private fun objects(o: JSONObject, key: String, max: Int, where: String, errors: MutableList<String>): List<Pair<Int, JSONObject>> {
        val v = o.opt(key)
        if (v == null || v === JSONObject.NULL) return emptyList()
        if (v !is JSONArray) {
            errors += "$where$key must be a list"
            return emptyList()
        }
        if (v.length() > max) {
            errors += "$where$key has more than $max entries"
            return emptyList()
        }
        val out = ArrayList<Pair<Int, JSONObject>>()
        for (i in 0 until v.length()) {
            val item = v.opt(i) as? JSONObject
            if (item == null) errors += "$where$key[$i] must be an object" else out += i to item
        }
        return out
    }

    /** A [PluginToolSpec] (android-service action), an [HttpToolSpec] (http action), or null when the tool had errors. */
    private fun parseTool(
        o: JSONObject,
        index: Int,
        allowed: Set<String>,
        httpPermissions: List<HttpPermission>,
        secretNames: Set<String>,
        errors: MutableList<String>,
    ): Any? {
        val before = errors.size
        val name = o.opt("name") as? String
        val nameOk = name != null && TOOL_NAME.matches(name)
        val label = if (nameOk) "tool '$name'" else "tools[$index]"
        if (!nameOk) errors += "$label: name must be 2 to 40 characters: lowercase letters, digits and underscores, starting with a letter"
        val description = shown(o, "description", 1, 300, label, errors)
        val params = parseParams(o, label, errors)
        val actionObject = o.opt("action") as? JSONObject
        if (actionObject == null) {
            errors += "$label: action is required"
            return null
        }
        val paramNames = params.map { it.name }.toSet()
        if (actionObject.opt("kind") == HttpPlugin.KIND) {
            val http = HttpManifest.action(actionObject, label, paramNames, httpPermissions, secretNames, errors)
            if (errors.size > before || name == null || http == null) return null
            return HttpToolSpec(name, description, params, http)
        }
        val action = parseAction(actionObject, label, paramNames, allowed, errors)
        if (errors.size > before || name == null || action == null) return null
        return PluginToolSpec(name, description, params, action)
    }

    private fun parseParams(tool: JSONObject, label: String, errors: MutableList<String>): List<ToolParam> {
        val seen = HashSet<String>()
        val out = ArrayList<ToolParam>()
        for ((i, p) in objects(tool, "params", MAX_PARAMS, "$label: ", errors)) {
            val name = p.opt("name") as? String
            if (name == null || !PARAM_NAME.matches(name)) {
                errors += "$label: params[$i] name must be lowercase letters, digits and underscores, starting with a letter"
                continue
            }
            if (!seen.add(name)) {
                errors += "$label: param '$name' is listed twice"
                continue
            }
            val description = shown(p, "description", 0, 200, "$label param '$name'", errors)
            val requiredValue = p.opt("required")
            if (requiredValue != null && requiredValue !== JSONObject.NULL && requiredValue !is Boolean) {
                errors += "$label param '$name': required must be true or false"
            }
            val lengthValue = p.opt("maxLength")
            val maxLength = when (lengthValue) {
                null -> DEFAULT_PARAM_LENGTH
                is Int -> lengthValue
                else -> {
                    errors += "$label param '$name': maxLength must be a whole number"
                    DEFAULT_PARAM_LENGTH
                }
            }
            if (maxLength !in 1..MAX_PARAM_LENGTH) {
                errors += "$label param '$name': maxLength must be 1 to $MAX_PARAM_LENGTH"
            }
            out += ToolParam(name, description, requiredValue == true, maxLength)
        }
        return out
    }

    private fun parseAction(
        a: JSONObject,
        label: String,
        paramNames: Set<String>,
        allowed: Set<String>,
        errors: MutableList<String>,
    ): ServiceAction? {
        val before = errors.size
        if (a.opt("kind") != KIND_ANDROID_SERVICE) errors += "$label: action.kind must be \"$KIND_ANDROID_SERVICE\" or \"${HttpPlugin.KIND}\""

        val pkg = a.opt("package") as? String
        if (pkg == null) {
            errors += "$label: action.package is required"
        } else {
            val problem = packageProblem(pkg)
            if (problem != null) {
                errors += "$label: action.package: $problem"
            } else if (pkg !in allowed) {
                errors += "$label: it targets $pkg but the plugin does not ask for ${Permission.EXTERNAL_APP_PREFIX}$pkg"
            }
        }
        val className = matching(a, "class", CLASS_NAME, "$label: action", errors)
        val intentAction = matching(a, "intentAction", INTENT_ACTION, "$label: action", errors)

        val extras = LinkedHashMap<String, ExtraValue>()
        val extrasValue = a.opt("extras")
        if (extrasValue != null && extrasValue !== JSONObject.NULL) {
            if (extrasValue !is JSONObject) {
                errors += "$label: action.extras must be an object"
            } else if (extrasValue.length() > MAX_EXTRAS) {
                errors += "$label: action.extras has more than $MAX_EXTRAS entries"
            } else {
                for (key in extrasValue.keys()) {
                    if (!EXTRA_KEY.matches(key)) {
                        errors += "$label: extra name '$key' has characters that are not allowed"
                        continue
                    }
                    val value = extraValue(extrasValue.opt(key), "$label: extra '$key'", errors) ?: continue
                    for (placeholder in placeholders(value)) {
                        if (placeholder !in paramNames) {
                            errors += "$label: extra '$key' uses {$placeholder} but the tool has no param with that name"
                        }
                    }
                    extras[key] = value
                }
            }
        }

        val resultValue = a.opt("result")
        val result = when {
            resultValue == null || resultValue === JSONObject.NULL -> null
            resultValue is JSONObject -> parseResult(resultValue, label, errors)
            else -> {
                errors += "$label: action.result must be an object"
                null
            }
        }

        val timeoutValue = a.opt("timeoutMs")
        val timeout = when (timeoutValue) {
            null -> DEFAULT_TIMEOUT_MS
            is Int -> timeoutValue.toLong()
            is Long -> timeoutValue
            else -> {
                errors += "$label: action.timeoutMs must be a whole number"
                DEFAULT_TIMEOUT_MS
            }
        }
        if (timeout !in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            errors += "$label: action.timeoutMs must be $MIN_TIMEOUT_MS to $MAX_TIMEOUT_MS"
        }

        if (errors.size > before || pkg == null || className == null || intentAction == null) return null
        return ServiceAction(pkg, className, intentAction, extras, result, timeout)
    }

    private fun parseResult(r: JSONObject, label: String, errors: MutableList<String>): ResultSpec? {
        val before = errors.size
        val where = "$label: action.result"
        val pendingIntentExtra = matching(r, "pendingIntentExtra", EXTRA_KEY, where, errors)
        val bundleExtra = matching(r, "bundleExtra", EXTRA_KEY, where, errors)
        val outputKey = matching(r, "outputKey", EXTRA_KEY, where, errors)
        val errorOutputKey = optionalKey(r, "errorOutputKey", where, errors)
        val exitCodeKey = optionalKey(r, "exitCodeKey", where, errors)
        val errorCodeKey = optionalKey(r, "errorCodeKey", where, errors)
        val errorMessageKey = optionalKey(r, "errorMessageKey", where, errors)
        val okValue = r.opt("errorCodeOk")
        val errorCodeOk = when (okValue) {
            null, JSONObject.NULL -> null
            is Int -> okValue
            else -> {
                errors += "$where: errorCodeOk must be a whole number"
                null
            }
        }
        if (errors.size > before || pendingIntentExtra == null || bundleExtra == null || outputKey == null) return null
        return ResultSpec(pendingIntentExtra, bundleExtra, outputKey, errorOutputKey, exitCodeKey, errorCodeKey, errorCodeOk, errorMessageKey)
    }

    private fun matching(o: JSONObject, key: String, regex: Regex, where: String, errors: MutableList<String>): String? {
        val v = o.opt(key) as? String
        if (v == null || !regex.matches(v)) {
            errors += "$where.$key is missing or has characters that are not allowed"
            return null
        }
        return v
    }

    private fun optionalKey(o: JSONObject, key: String, where: String, errors: MutableList<String>): String? {
        val v = o.opt(key)
        if (v == null || v === JSONObject.NULL) return null
        if (v !is String || !EXTRA_KEY.matches(v)) {
            errors += "$where.$key has characters that are not allowed"
            return null
        }
        return v
    }

    private fun extraValue(v: Any?, label: String, errors: MutableList<String>): ExtraValue? = when (v) {
        is String -> if (v.length <= MAX_EXTRA_TEXT) {
            ExtraValue.Text(v)
        } else {
            errors += "$label is longer than $MAX_EXTRA_TEXT characters"
            null
        }
        is Boolean -> ExtraValue.Flag(v)
        is Int -> ExtraValue.IntValue(v)
        is Long -> if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) {
            ExtraValue.IntValue(v.toInt())
        } else {
            errors += "$label is too large"
            null
        }
        is JSONArray -> textList(v, label, errors)
        else -> {
            errors += "$label must be text, true or false, a whole number, or a list of text"
            null
        }
    }

    private fun textList(array: JSONArray, label: String, errors: MutableList<String>): ExtraValue? {
        if (array.length() > MAX_LIST) {
            errors += "$label has more than $MAX_LIST items"
            return null
        }
        val values = ArrayList<String>()
        for (i in 0 until array.length()) {
            val item = array.opt(i) as? String
            if (item == null || item.length > MAX_EXTRA_TEXT) {
                errors += "$label item $i must be text of at most $MAX_EXTRA_TEXT characters"
                return null
            }
            values += item
        }
        return ExtraValue.TextList(values)
    }

    private fun placeholders(v: ExtraValue): Set<String> = when (v) {
        is ExtraValue.Text -> namesIn(v.value)
        is ExtraValue.TextList -> v.values.flatMap { namesIn(it) }.toSet()
        else -> emptySet()
    }

    private fun namesIn(text: String): Set<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    private fun parseCommand(o: JSONObject, index: Int, errors: MutableList<String>): PromptCommand? {
        val before = errors.size
        val name = o.opt("name") as? String
        val label = if (name != null && COMMAND_NAME.matches(name)) "prompt command '$name'" else "promptCommands[$index]"
        if (name == null || !COMMAND_NAME.matches(name)) {
            errors += "$label: name must be 2 to 40 characters: lowercase letters, digits and hyphens, starting with a letter"
        }
        val description = shown(o, "description", 0, 200, label, errors)
        val template = o.opt("template") as? String
        if (template == null || template.isBlank() || template.length > MAX_TEMPLATE) {
            errors += "$label: template must be text of 1 to $MAX_TEMPLATE characters"
        } else {
            for (p in namesIn(template)) {
                if (p !in PROMPT_PLACEHOLDERS) errors += "$label: {$p} is not available; use ${PROMPT_PLACEHOLDERS.joinToString { "{$it}" }}"
            }
        }
        if (errors.size > before || name == null || template == null) return null
        return PromptCommand(name, description, template)
    }

    private fun parseSnippet(o: JSONObject, index: Int, errors: MutableList<String>): Snippet? {
        val before = errors.size
        val prefix = o.opt("prefix") as? String
        val label = if (prefix != null && SNIPPET_PREFIX.matches(prefix)) "snippet '$prefix'" else "snippets[$index]"
        if (prefix == null || !SNIPPET_PREFIX.matches(prefix)) {
            errors += "$label: prefix must be 1 to 30 letters, digits, dots, hyphens or underscores"
        }
        val description = shown(o, "description", 0, 200, label, errors)
        val body = o.opt("body") as? String
        if (body == null || body.isEmpty() || body.length > MAX_TEMPLATE) {
            errors += "$label: body must be text of 1 to $MAX_TEMPLATE characters"
        }
        if (errors.size > before || prefix == null || body == null) return null
        return Snippet(prefix, description, body)
    }
}
