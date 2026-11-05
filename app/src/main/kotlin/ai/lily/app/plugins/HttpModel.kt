package ai.lily.app.plugins

/** Only these. There is no DELETE, HEAD or OPTIONS: a plugin cannot remove things over http. */
enum class HttpMethod { GET, POST, PUT, PATCH }

/**
 * A JSON body as a tree, so the app writes the JSON itself and a value from a tool argument can never break out of its
 * string. Text may contain {param}, {secret.name} and {setting.name}.
 */
sealed interface BodyNode {
    data class Text(val value: String) : BodyNode
    data class Num(val value: Long) : BodyNode
    data class Flag(val value: Boolean) : BodyNode
    data class Obj(val fields: Map<String, BodyNode>) : BodyNode
    data class Arr(val items: List<BodyNode>) : BodyNode
}

/**
 * "Send this https request." [url] may use {param}, {secret.name} and {setting.name} after the host, never in it; header
 * values may use only {secret.name} and {setting.name}. [permission] is the one http permission this request falls under;
 * the parser checked that the url is on its host and inside its pinned path. [untrusted] marks the answer as text written
 * by other people, so Lily is told to treat it as data and not as instructions.
 */
data class HttpAction(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String>,
    val body: BodyNode?,
    val timeoutMs: Long,
    val permission: Permission.Http,
    val untrusted: Boolean = false,
) : ToolAction {
    override val permissionKey: String get() = permission.key
    override val target: String get() = permission.host + permission.pathPrefix
    override val alwaysAsks: Boolean get() = method != HttpMethod.GET
}

/** A value the person fills in for a plugin. A secret is kept in encrypted storage and is never shown again or sent to Lily. */
data class SettingSpec(
    val name: String,
    val label: String,
    val description: String,
    val secret: Boolean,
    val required: Boolean,
    val maxLength: Int,
) {
    /** Why [value] cannot be saved, or null. A value is one line with no invisible characters. */
    fun problemWith(value: String): String? = when {
        value.isBlank() -> "Enter a value."
        value.trim().length > maxLength -> "That is longer than $maxLength characters."
        Visible.hasHidden(value.trim()) -> "A value is one line with no invisible characters."
        else -> null
    }
}
