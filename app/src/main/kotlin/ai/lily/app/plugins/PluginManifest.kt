package ai.lily.app.plugins

/**
 * What a plugin declares. A plugin is data only: the app never loads or runs code from a plugin.
 * Everything a plugin can do goes through a bridge the app provides, and only after the person allows it.
 * [author], [icon] and [accent] are optional and only change how the plugin's card looks; "" means not given.
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
    /** Values the person fills in on the plugin's page (a token, a channel list). Secret ones live in encrypted storage. */
    val settings: List<SettingSpec> = emptyList(),
    /**
     * The name of one of the app's own screens (one of [ManifestParser.SCREENS]) that the plugin's Open button shows, or "" for
     * none. It only names a screen the app already has: it adds no code, no permission and no ability.
     */
    val screen: String = "",
)

/** Something a plugin asks the person to allow. */
sealed interface Permission {
    /** Stable text form, for example "external-app:com.termux". Allowed permissions are stored by this key. */
    val key: String

    /** Start a service in another installed app. */
    data class ExternalApp(val packageName: String) : Permission {
        override val key: String get() = Permission.EXTERNAL_APP_PREFIX + packageName
    }

    /**
     * Send https requests to one host, exactly: no sub-domains, no port, no other host after a redirect. [pathPrefix] is
     * "" or a pinned start of the path such as "/api/v10/"; a request outside it is refused.
     */
    data class Http(val host: String, val pathPrefix: String = "") : Permission {
        override val key: String get() = Permission.HTTP_PREFIX + host + pathPrefix
    }

    companion object {
        const val EXTERNAL_APP_PREFIX = "external-app:"
        const val HTTP_PREFIX = "http:"
    }
}

/** What a tool does when it runs. The permission it needs is [permissionKey]. */
sealed interface ToolAction {
    val permissionKey: String

    /** The app package or host the person is told about. */
    val target: String

    /** True when the tool changes something somewhere else: it asks every time and "don't ask again" is never offered. */
    val alwaysAsks: Boolean
}

/** A tool the coding and other workers can call. */
data class PluginToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam>,
    val action: ToolAction,
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
) : ToolAction {
    override val permissionKey: String get() = Permission.ExternalApp(packageName).key
    override val target: String get() = packageName
    override val alwaysAsks: Boolean get() = false
}

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
