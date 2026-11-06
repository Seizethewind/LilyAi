package ai.lily.app.discord

import ai.lily.app.plugins.ConfirmRequest
import ai.lily.app.plugins.ConfirmationGate
import ai.lily.app.plugins.FailReason
import ai.lily.app.plugins.HttpBridge
import ai.lily.app.plugins.HttpCall
import ai.lily.app.plugins.HttpGuard
import ai.lily.app.plugins.HttpMethod
import ai.lily.app.plugins.HttpReply
import ai.lily.app.plugins.HttpTemplates
import ai.lily.app.plugins.InstalledPlugin
import ai.lily.app.plugins.Permission
import ai.lily.app.plugins.PluginRegistry
import ai.lily.app.plugins.PluginSecrets
import ai.lily.app.plugins.Visible
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.cancellation.CancellationException

sealed interface DiscordResult<out T> {
    data class Ok<out T>(val value: T) : DiscordResult<T>

    /**
     * [message] is for the person to read. [retryAfterMs] is set when Discord asked us to slow down. [needsSetup] means the plugin
     * is not ready (not installed, off, not allowed, no token, or the token was refused). [denied] means the person said no.
     */
    data class Failed(
        val message: String,
        val retryAfterMs: Long? = null,
        val needsSetup: Boolean = false,
        val denied: Boolean = false,
    ) : DiscordResult<Nothing>
}

/**
 * What the Discord screen uses to talk to Discord, as the person's own bot. It is the plugin's http capability used by the app
 * itself, so the plugin's rules still hold on every call:
 * - the Discord plugin must be installed, turned on and allowed to reach discord.com/api/v10/, and have a bot token (read again
 *   each time, so turning it off or taking the permission back stops this at once);
 * - the request goes through [HttpGuard] and [HttpBridge], so it can only reach that host and path, and nothing else;
 * - reads follow the plugin's "Run without asking each time" switch for the matching tool (discord_list_channels for servers and
 *   channels, discord_read_messages for messages) and ask otherwise; sending asks every time, whatever is stored;
 * - there is no call that deletes, kicks, bans or changes roles, and the http layer has no DELETE;
 * - the token is only put in the Authorization header, is never shown in a question and is removed from every message here.
 * Discord's Gateway (live updates) is a websocket and is not reachable, so the screen polls [messages] with `after`.
 */
class DiscordApi(
    private val registry: PluginRegistry,
    private val secrets: PluginSecrets,
    private val http: HttpBridge,
    private val gate: ConfirmationGate,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var blockedUntil = 0L

    /** The servers the bot is in (the first 200). */
    suspend fun guilds(): DiscordResult<List<DiscordGuild>> =
        request(TOOL_LIST, HttpMethod.GET, "$BASE/users/@me/guilds?limit=200", null, emptyList(), GET_TIMEOUT_MS) { DiscordParse.guilds(it) }

    /** Every channel the bot can see in one server, in no particular order; use [groupChannels] to lay them out. */
    suspend fun channels(guildId: String): DiscordResult<List<DiscordChannel>> {
        if (!Snowflake.isValid(guildId)) return notAnId("server")
        return request(TOOL_LIST, HttpMethod.GET, "$BASE/guilds/$guildId/channels", null, emptyList(), GET_TIMEOUT_MS) { DiscordParse.channels(it) }
    }

    /**
     * Messages of one channel, oldest first. With no cursor it is the newest [limit]; [after] gives the ones newer than that id
     * (for polling) and [before] the ones older (for scrolling back). Only one of the two at a time.
     */
    suspend fun messages(
        channelId: String,
        limit: Int = DEFAULT_LIMIT,
        after: String? = null,
        before: String? = null,
    ): DiscordResult<List<DiscordMessage>> {
        if (!Snowflake.isValid(channelId)) return notAnId("channel")
        if (after != null && before != null) return DiscordResult.Failed("Ask for messages after one or before one, not both.")
        for (cursor in listOfNotNull(after, before)) {
            if (!Snowflake.isValid(cursor)) return notAnId("message")
        }
        val query = StringBuilder("?limit=").append(limit.coerceIn(1, MAX_LIMIT))
        if (after != null) query.append("&after=").append(after)
        if (before != null) query.append("&before=").append(before)
        return request(TOOL_READ, HttpMethod.GET, "$BASE/channels/$channelId/messages$query", null, emptyList(), GET_TIMEOUT_MS) { DiscordParse.messages(it) }
    }

    /** Posts a message as the bot. It asks the person every time, and the message cannot ping @everyone, @here or roles. */
    suspend fun send(channelId: String, content: String): DiscordResult<DiscordMessage> {
        if (!Snowflake.isValid(channelId)) return notAnId("channel")
        if (content.isBlank()) return DiscordResult.Failed("Write something first.")
        if (content.length > MAX_MESSAGE) return DiscordResult.Failed("A message can be at most $MAX_MESSAGE characters; this one is ${content.length}.")
        if (content.contains('\u0000')) return DiscordResult.Failed("A message cannot contain a null character.")
        val body = JSONObject()
            .put("content", content)
            .put("allowed_mentions", JSONObject().put("parse", JSONArray()))
            .toString()
        val shown = listOf(
            "Channel" to channelId,
            "Message" to shorten(Visible.escape(content)),
            "Mentions" to "none: @everyone, @here and roles are switched off",
        )
        return request(TOOL_SEND, HttpMethod.POST, "$BASE/channels/$channelId/messages", body, shown, POST_TIMEOUT_MS) {
            DiscordParse.message(JSONObject(it)) ?: throw IllegalStateException("no message in the answer")
        }
    }

    private sealed interface Access {
        class Ok(val plugin: InstalledPlugin, val token: String) : Access
        class No(val message: String) : Access
    }

    /** Read again on every call, like a plugin tool does, so nothing is remembered from when the screen opened. */
    private fun access(): Access {
        val plugin = registry.get(PLUGIN_ID) ?: return Access.No("The Discord plugin is not installed.")
        if (!plugin.state.enabled) return Access.No("The Discord plugin is turned off. Turn it on in Plugins.")
        if (PERMISSION.key !in plugin.state.granted) {
            return Access.No("Allow the Discord plugin to send requests to discord.com/api/v10/ on its page in Plugins.")
        }
        val token = secrets.get(PLUGIN_ID, "token")
        if (token.isNullOrBlank()) return Access.No("Paste your bot token on the Discord plugin's page in Plugins.")
        return Access.Ok(plugin, token)
    }

    private suspend fun <T> request(
        tool: String,
        method: HttpMethod,
        url: String,
        body: String?,
        extra: List<Pair<String, String>>,
        timeoutMs: Long,
        parse: (String) -> T,
    ): DiscordResult<T> {
        val ready = when (val a = access()) {
            is Access.No -> return DiscordResult.Failed(a.message, needsSetup = true)
            is Access.Ok -> a
        }
        val wait = blockedUntil - now()
        if (wait > 0) return DiscordResult.Failed(slowDown(wait), retryAfterMs = wait)
        HttpGuard.checkUrl(url, PERMISSION)?.let { return DiscordResult.Failed("That address is not allowed: $it") }
        val authorization = "Bot ${ready.token}"
        if (HttpGuard.headerValueProblem("Authorization", authorization) != null) {
            return DiscordResult.Failed("The bot token has a character a request cannot carry. Paste it again.", needsSetup = true)
        }

        val changes = method != HttpMethod.GET
        if (changes || tool !in ready.plugin.state.autoApprove) {
            val details = ArrayList<Pair<String, String>>()
            details += "Method" to method.name
            details += "Address" to url
            details += "Header Authorization" to "Bot [secret:token]"
            details += extra
            val request = ConfirmRequest(
                ready.plugin.manifest.id,
                ready.plugin.manifest.name,
                tool,
                PERMISSION.host + PERMISSION.pathPrefix,
                details,
                isHttp = true,
                changes = changes,
            )
            if (!gate.confirm(request)) return DiscordResult.Failed("You said no, so nothing was sent.", denied = true)
        }

        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = authorization
        headers["User-Agent"] = USER_AGENT
        val reply = try {
            http.call(HttpCall(method, url, headers, body, timeoutMs, PERMISSION, maxBytes = MAX_ANSWER_BYTES))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HttpReply.Failed(FailReason.ERROR, e.message ?: e.javaClass.simpleName)
        }
        return when (reply) {
            is HttpReply.Failed -> DiscordResult.Failed(scrub("Could not reach Discord: ${reply.message}", ready.token))
            is HttpReply.Done -> answer(reply, parse, ready.token)
        }
    }

    private fun <T> answer(reply: HttpReply.Done, parse: (String) -> T, token: String): DiscordResult<T> {
        val status = reply.status
        if (status in 200..299) {
            if (reply.truncated) return DiscordResult.Failed("Discord's answer was too long to read. Ask for fewer messages.")
            return try {
                DiscordResult.Ok(parse(reply.body))
            } catch (e: Exception) {
                DiscordResult.Failed("Discord's answer was not what this app expects.")
            }
        }
        if (status == 429) {
            val seconds = DiscordParse.retryAfterSeconds(reply.body)
            val wait = if (seconds == null) DEFAULT_WAIT_MS else (seconds * 1000).toLong().coerceIn(MIN_WAIT_MS, MAX_WAIT_MS)
            blockedUntil = now() + wait
            return DiscordResult.Failed(slowDown(wait), retryAfterMs = wait)
        }
        val base = when (status) {
            401 -> "Discord refused the bot token (401). Reset it on the Bot page of the developer portal and paste the new one."
            403 -> "The bot may not do that here (403). Check its role in the server: View Channels, Read Message History, Send Messages."
            404 -> "Discord could not find that (404). The id is wrong, or the bot is not in that server."
            else -> "Discord answered http $status."
        }
        val detail = DiscordParse.errorText(reply.body)
        val text = if (detail.isEmpty()) base else "$base ($detail)"
        return DiscordResult.Failed(scrub(text, token), needsSetup = status == 401)
    }

    private fun slowDown(ms: Long): String = "Discord asked to slow down. Try again in ${(ms + 999) / 1000} s."

    private fun notAnId(what: String): DiscordResult.Failed = DiscordResult.Failed("That is not a valid $what id (a long number).")

    private fun scrub(text: String, token: String): String = HttpTemplates.scrub(text, listOf(token))

    private fun shorten(text: String): String =
        if (text.length <= MAX_SHOWN) text else text.take(MAX_SHOWN) + "... (${text.length - MAX_SHOWN} more characters)"

    companion object {
        const val PLUGIN_ID = "lily.discord"
        const val BASE = "https://discord.com/api/v10"
        const val TOOL_LIST = "discord_list_channels"
        const val TOOL_READ = "discord_read_messages"
        const val TOOL_SEND = "discord_send_message"
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
        const val MAX_MESSAGE = 2000

        /** How much of an answer is read: a full page of messages with embeds, far more than a plugin tool's 16 KB. */
        const val MAX_ANSWER_BYTES = 512 * 1024

        private const val USER_AGENT = "DiscordBot (https://github.com/Seizethewind/LilyAi, 1.1.0)"
        private const val GET_TIMEOUT_MS = 20_000L
        private const val POST_TIMEOUT_MS = 30_000L
        private const val DEFAULT_WAIT_MS = 5_000L
        private const val MIN_WAIT_MS = 1_000L
        private const val MAX_WAIT_MS = 60_000L
        private const val MAX_SHOWN = 2_000

        /** The one permission this uses: the same one the Discord plugin asks for. */
        val PERMISSION = Permission.Http("discord.com", "/api/v10/")
    }
}
