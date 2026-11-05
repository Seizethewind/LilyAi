package ai.lily.app.plugins

import ai.lily.app.tools.Tool
import ai.lily.app.tools.ToolSystem
import kotlin.coroutines.cancellation.CancellationException

/** How the app starts another app's service. The Android version is [AndroidServiceBridge]; tests use a fake. */
interface ServiceBridge {
    suspend fun call(call: ServiceCall): ServiceReply
}

data class ServiceCall(
    val packageName: String,
    val className: String,
    val intentAction: String,
    val extras: Map<String, ExtraValue>,
    val result: ResultSpec?,
    val timeoutMs: Long,
)

enum class FailReason { APP_NOT_INSTALLED, NOT_ALLOWED, TIMEOUT, ERROR }

sealed interface ServiceReply {
    /** The call was made and no answer was asked for. */
    object Sent : ServiceReply

    data class Done(
        val output: String,
        val errorOutput: String?,
        val exitCode: Int?,
        val errorCode: Int?,
        val errorMessage: String?,
    ) : ServiceReply

    data class Failed(val reason: FailReason, val message: String) : ServiceReply
}

/**
 * What the person is asked to approve. [details] is every value that will be sent, in order, with hidden characters
 * made visible, so the screen can show exactly what will run. For a web request [targetPackage] holds the host and
 * secrets appear only as {secret.name}.
 */
data class ConfirmRequest(
    val pluginId: String,
    val pluginName: String,
    val toolName: String,
    val targetPackage: String,
    val details: List<Pair<String, String>>,
)

interface ConfirmationGate {
    suspend fun confirm(request: ConfirmRequest): Boolean
}

/** Used until a screen exists to ask: nothing runs. */
object DenyAllGate : ConfirmationGate {
    override suspend fun confirm(request: ConfirmRequest): Boolean = false
}

/**
 * One tool from a plugin, as the workers see it. Nothing is remembered from when it was registered: whether the plugin
 * is on, what is allowed and what the tool does are read again on every call, so turning a plugin off, taking a
 * permission back or updating it takes effect at once. [http] runs web tools; without it a web tool answers with an error.
 */
class PluginTool(
    private val pluginId: String,
    override val name: String,
    private val registry: PluginRegistry,
    private val bridge: ServiceBridge,
    private val gate: ConfirmationGate,
    private val http: HttpRunner? = null,
) : Tool {
    override suspend fun execute(args: Map<String, String>): String {
        val plugin = registry.get(pluginId) ?: return "error: the plugin '$pluginId' is not installed"
        val label = plugin.manifest.name
        if (!plugin.state.enabled) return "error: the plugin '$label' is turned off"
        val spec = plugin.manifest.tools.firstOrNull { it.name == name }
        if (spec == null) {
            val web = plugin.manifest.httpTools.firstOrNull { it.name == name }
                ?: return "error: the plugin '$label' no longer has a tool called '$name'"
            return executeWeb(plugin, web, args)
        }
        val action = spec.action
        if (Permission.ExternalApp(action.packageName).key !in plugin.state.granted) {
            return "error: the person has not allowed '$label' to use ${action.packageName}"
        }
        val values = when (val checked = checkArguments(spec.params, args)) {
            is Checked.Bad -> return "error: ${checked.message}"
            is Checked.Good -> checked.values
        }
        val extras = Substitution.resolve(action.extras, values)
        if (name !in plugin.state.autoApprove) {
            val request = ConfirmRequest(plugin.manifest.id, label, name, action.packageName, describe(extras))
            if (!gate.confirm(request)) return "denied: the person did not approve this, so nothing was run"
        }
        val reply = try {
            bridge.call(ServiceCall(action.packageName, action.className, action.intentAction, extras, action.result, action.timeoutMs))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ServiceReply.Failed(FailReason.ERROR, e.message ?: e.javaClass.simpleName)
        }
        return format(reply, action.result)
    }

    private suspend fun executeWeb(plugin: InstalledPlugin, spec: HttpToolSpec, args: Map<String, String>): String {
        val label = plugin.manifest.name
        val action = spec.action
        if (action.permissionKey !in plugin.state.granted) {
            return "error: the person has not allowed '$label' to use ${action.host}"
        }
        val runner = http ?: return "error: web requests are not available here"
        val values = when (val checked = checkArguments(spec.params, args)) {
            is Checked.Bad -> return "error: ${checked.message}"
            is Checked.Good -> checked.values
        }
        val ready = when (val prepared = runner.prepare(plugin.manifest.id, action, values)) {
            is HttpRunner.Prepared.Bad -> return "error: ${prepared.message}"
            is HttpRunner.Prepared.Ready -> prepared
        }
        // Only a plain read may skip the question, even if a hand-edited file says otherwise.
        val skipQuestion = name in plugin.state.autoApprove && action.method == "GET"
        if (!skipQuestion) {
            val request = ConfirmRequest(plugin.manifest.id, label, name, action.host, ready.view)
            if (!gate.confirm(request)) return "denied: the person did not approve this, so nothing was sent"
        }
        return runner.send(ready.request, ready.secretValues)
    }

    private sealed interface Checked {
        class Good(val values: Map<String, String>) : Checked
        class Bad(val message: String) : Checked
    }

    private fun checkArguments(params: List<ToolParam>, args: Map<String, String>): Checked {
        val known = params.map { it.name }.toSet()
        val unknown = args.keys.filter { it !in known }
        if (unknown.isNotEmpty()) {
            return Checked.Bad("unknown argument ${unknown.joinToString { "'$it'" }}; this tool takes: ${known.joinToString().ifEmpty { "nothing" }}")
        }
        val values = LinkedHashMap<String, String>()
        for (p in params) {
            val v = args[p.name]
            if (v == null || v.isBlank()) {
                if (p.required) return Checked.Bad("'${p.name}' is required")
                continue
            }
            if (v.length > p.maxLength) return Checked.Bad("'${p.name}' is longer than ${p.maxLength} characters")
            if (v.contains('\u0000')) return Checked.Bad("'${p.name}' contains a null character")
            values[p.name] = v
        }
        return Checked.Good(values)
    }

    private fun describe(extras: Map<String, ExtraValue>): List<Pair<String, String>> = extras.map { (key, v) ->
        key to when (v) {
            is ExtraValue.Text -> shorten(Visible.escape(v.value))
            is ExtraValue.TextList -> shorten(v.values.joinToString("\n") { Visible.escape(it) })
            is ExtraValue.Flag -> v.value.toString()
            is ExtraValue.IntValue -> v.value.toString()
        }
    }

    private fun shorten(text: String): String =
        if (text.length <= MAX_SHOWN) text else text.take(MAX_SHOWN) + "... (${text.length - MAX_SHOWN} more characters)"

    private fun format(reply: ServiceReply, spec: ResultSpec?): String = when (reply) {
        is ServiceReply.Sent -> "sent; this tool returns no result"
        is ServiceReply.Failed -> "error: ${reasonText(reply.reason)}: ${reply.message}"
        is ServiceReply.Done -> {
            val ok = spec?.errorCodeOk
            if (reply.errorCode != null && ok != null && reply.errorCode != ok) {
                "error: the other app reported a problem (code ${reply.errorCode}): ${reply.errorMessage.orEmpty().ifBlank { "no message" }}"
            } else {
                buildString {
                    append("exit=").append(reply.exitCode?.toString() ?: "unknown").append('\n')
                    append(tail(reply.output))
                    if (!reply.errorOutput.isNullOrBlank()) append("\n[stderr]\n").append(tail(reply.errorOutput))
                }
            }
        }
    }

    private fun reasonText(r: FailReason): String = when (r) {
        FailReason.APP_NOT_INSTALLED -> "the app is not installed"
        FailReason.NOT_ALLOWED -> "Android or the app refused"
        FailReason.TIMEOUT -> "no answer in time"
        FailReason.ERROR -> "the call failed"
    }

    /** Keeps the end of long output, where errors usually are. */
    private fun tail(text: String): String =
        if (text.length <= MAX_OUTPUT) text else "[${text.length - MAX_OUTPUT} characters cut from the start]\n" + text.takeLast(MAX_OUTPUT)

    private companion object {
        const val MAX_OUTPUT = 16_000
        const val MAX_SHOWN = 2_000
    }
}

/** Fills {param} placeholders in one pass: a value that itself looks like a placeholder is left as it is. */
internal object Substitution {
    fun resolve(extras: Map<String, ExtraValue>, values: Map<String, String>): Map<String, ExtraValue> {
        val out = LinkedHashMap<String, ExtraValue>()
        for ((key, extra) in extras) {
            when (extra) {
                is ExtraValue.Text -> {
                    val filled = fill(extra.value, values)
                    // An extra that ends up empty (its optional param was not given) is left out, not sent blank.
                    if (filled.isNotBlank()) out[key] = ExtraValue.Text(filled)
                }
                is ExtraValue.TextList -> out[key] = ExtraValue.TextList(extra.values.map { fill(it, values) })
                else -> out[key] = extra
            }
        }
        return out
    }

    private fun fill(text: String, values: Map<String, String>): String =
        ManifestParser.PLACEHOLDER.replace(text) { values[it.groupValues[1]].orEmpty() }
}

/** Puts the tools of every plugin that is on and allowed into the tool system the workers use. */
class PluginHost(
    private val registry: PluginRegistry,
    private val bridge: ServiceBridge,
    private val gate: ConfirmationGate = DenyAllGate,
    private val http: HttpRunner? = null,
) {
    data class Report(val registered: List<String>, val skipped: List<String>)

    /**
     * Call this after the app's own tools are registered. A plugin can never replace one of them: a tool whose name is
     * already taken is skipped. Plugins installed or turned on later appear the next time this runs.
     */
    fun registerInto(tools: ToolSystem): Report {
        val registered = ArrayList<String>()
        val skipped = ArrayList<String>()
        for (plugin in registry.list()) {
            if (!plugin.state.enabled) continue
            for (spec in plugin.manifest.tools) {
                val where = "${plugin.manifest.id}/${spec.name}"
                if (Permission.ExternalApp(spec.action.packageName).key !in plugin.state.granted) {
                    skipped += "$where: permission not allowed"
                } else if (tools.get(spec.name) != null) {
                    skipped += "$where: a tool with that name already exists"
                } else {
                    tools.register(PluginTool(plugin.manifest.id, spec.name, registry, bridge, gate, http))
                    registered += spec.name
                }
            }
            for (spec in plugin.manifest.httpTools) {
                val where = "${plugin.manifest.id}/${spec.name}"
                if (spec.action.permissionKey !in plugin.state.granted) {
                    skipped += "$where: permission not allowed"
                } else if (tools.get(spec.name) != null) {
                    skipped += "$where: a tool with that name already exists"
                } else {
                    tools.register(PluginTool(plugin.manifest.id, spec.name, registry, bridge, gate, http))
                    registered += spec.name
                }
            }
        }
        return Report(registered, skipped)
    }
}
