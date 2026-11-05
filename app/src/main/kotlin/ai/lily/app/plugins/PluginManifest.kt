package ai.lily.app.plugins

/**
 * What a plugin declares. A plugin is data only: the app never loads or runs code from a plugin.
 * Everything a plugin can do goes through a bridge the app provides, and only after the person allows it.
 * [author], [icon] and [accent] are optional and only change how the plugin's card looks; "" means not given.
 * [tools] start a service in another app; [httpTools] make a web request. Tool names are unique across both lists.
 * [secrets] are values the person types in once (see [PluginSecrets]); a manifest never holds their values.
 */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val permissions: List<Permission>,
    val tools: List<PluginToolSpec>,
    val promptCommands: List<PromptCommand>,
    val snippets: List<Snippet>,
    val author: String = "",
    val icon: String = "",
    val accent: String = "",
    val httpTools: List<HttpToolSpec> = emptyList(),
    val secrets: List<PluginSecret> = emptyList(),
) {
    fun toolNames(): Set<String> = (tools.map { it.name } + httpTools.map { it.name }).toSet()

    /** The tool's spec, of either kind. Two specs are equal exactly when the tool is unchanged. */
    fun specOf(name: String): Any? = tools.firstOrNull { it.name == name } ?: httpTools.firstOrNull { it.name == name }

    /** The [Permission.key] a tool needs, or null when there is no such tool. */
    fun permissionKeyOf(name: String): String? =
        tools.firstOrNull { it.name == name }?.let { Permission.ExternalApp(it.action.packageName).key }
            ?: httpTools.firstOrNull { it.name == name }?.action?.permissionKey

    /** False for a web request that is not a plain read: it asks every time and "don't ask again" is refused. */
    fun mayRunWithoutAsking(name: String): Boolean =
        httpTools.firstOrNull { it.name == name }?.let { it.action.method == "GET" } ?: true
}

/** Something a plugin asks the person to allow. */
sealed interface Permission {
    /** Stable text form, for example "external-app:com.termux". Allowed permissions are stored by this key. */
    val key: String

    /** Start a service in another installed app. */
    data class ExternalApp(val packageName: String) : Permission {
        override val key: String get() = Permission.EXTERNAL_APP_PREFIX + packageName
    }

    companion object {
        const val EXTERNAL_APP_PREFIX = "external-app:"
    }
}

/** A tool the coding and other workers can call. */
data class PluginToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val action: ServiceAction,
)

data class ToolParam(
    val name: String,
    val description: String,
    val required: Boolean,
    val maxLength: Int,
)

/**
 * "Start this service in that app with these extras." Every name, class and key here comes from the plugin, so the
 * app itself knows nothing about the app it talks to.
 * Text in [extras] may contain {param} placeholders; they are filled in with the tool's arguments.
 */
data class ServiceAction(
    val packageName: String,
    val className: String,
    val intentAction: String,
    val extras: Map<String, ExtraValue>,
    /** Null when the call is fire and forget. */
    val result: ResultSpec?,
    val timeoutMs: Long,
)

sealed interface ExtraValue {
    data class Text(val value: String) : ExtraValue
    data class TextList(val values: List<String>) : ExtraValue
    data class Flag(val value: Boolean) : ExtraValue
    data class IntValue(val value: Int) : ExtraValue
}

/** How the other app sends its answer back, and where in the answer to look. */
data class ResultSpec(
    /** The extra that carries our "send the answer here" PendingIntent. */
    val pendingIntentExtra: String,
    /** The extra, in the answer, that holds the result bundle. */
    val bundleExtra: String,
    val outputKey: String,
    val errorOutputKey: String?,
    val exitCodeKey: String?,
    val errorCodeKey: String?,
    /** The error code that means "no error". Any other code is treated as the other app failing. */
    val errorCodeOk: Int?,
    val errorMessageKey: String?,
)

/** A saved prompt. The template may use {selection}, {file} and {language}. */
data class PromptCommand(val name: String, val description: String, val template: String)

data class Snippet(val prefix: String, val description: String, val body: String)
