package ai.lily.app.discord

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads Discord's answers. Everything in them was written by other people, so names and text are cut to a sensible length and
 * control and text-direction characters are shown as \uXXXX instead of acting. A malformed answer throws; [DiscordApi] turns that
 * into a failure message.
 */
internal object DiscordParse {
    private const val MAX_NAME = 100
    private const val MAX_CONTENT = 4000
    private const val MAX_ERROR = 200

    fun guilds(text: String): List<DiscordGuild> {
        val array = JSONArray(text)
        val out = ArrayList<DiscordGuild>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = id(o, "id") ?: continue
            out += DiscordGuild(id, label(text(o, "name"), "server $id"))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun channels(text: String): List<DiscordChannel> {
        val array = JSONArray(text)
        val out = ArrayList<DiscordChannel>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = id(o, "id") ?: continue
            out += DiscordChannel(id, label(text(o, "name"), "channel-$id"), o.optInt("type", 0), id(o, "parent_id"), o.optInt("position", 0))
        }
        return out
    }

    /** Oldest first, whatever order Discord used. */
    fun messages(text: String): List<DiscordMessage> {
        val array = JSONArray(text)
        val out = ArrayList<DiscordMessage>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            message(o)?.let { out += it }
        }
        return out.sortedWith(Comparator { a, b -> Snowflake.compare(a.id, b.id) })
    }

    fun message(o: JSONObject): DiscordMessage? {
        val id = id(o, "id") ?: return null
        val author = o.optJSONObject("author")
        val shownName = author?.let { text(it, "global_name")?.takeIf { name -> name.isNotBlank() } ?: text(it, "username") }
        val files = ArrayList<String>()
        val attachments = o.optJSONArray("attachments")
        if (attachments != null) {
            for (i in 0 until attachments.length()) {
                val a = attachments.optJSONObject(i) ?: continue
                files += label(text(a, "filename"), "file")
            }
        }
        return DiscordMessage(
            id = id,
            authorId = author?.let { id(it, "id") }.orEmpty(),
            authorName = label(shownName, "unknown"),
            authorIsBot = author?.optBoolean("bot", false) ?: false,
            content = clean(text(o, "content").orEmpty(), MAX_CONTENT),
            timestamp = text(o, "timestamp").orEmpty().take(40),
            editedTimestamp = text(o, "edited_timestamp")?.take(40),
            attachments = files,
            embedCount = o.optJSONArray("embeds")?.length() ?: 0,
            type = o.optInt("type", 0),
        )
    }

    /** The "message" Discord gives with an error, cleaned and short, or "". */
    fun errorText(body: String): String = try {
        clean(text(JSONObject(body), "message").orEmpty(), MAX_ERROR).replace('\n', ' ').trim()
    } catch (e: Exception) {
        ""
    }

    /** Seconds Discord asks us to wait after a 429, or null. */
    fun retryAfterSeconds(body: String): Double? = try {
        JSONObject(body).optDouble("retry_after", Double.NaN).takeIf { !it.isNaN() && it >= 0 }
    } catch (e: Exception) {
        null
    }

    /** A value that is absent or JSON null is null (the platform's and the JVM's JSON libraries disagree on what optString gives for null). */
    private fun text(o: JSONObject, key: String): String? {
        val v = o.opt(key)
        return if (v == null || v === JSONObject.NULL) null else (v as? String) ?: v.toString()
    }

    private fun id(o: JSONObject, key: String): String? = text(o, key)?.takeIf { Snowflake.isValid(it) }

    /** One short line for a name. */
    private fun label(raw: String?, fallback: String): String {
        val one = clean(raw.orEmpty(), MAX_NAME).replace('\n', ' ').replace('\t', ' ').trim()
        return one.ifEmpty { fallback }
    }

    /** Cuts to [max] and shows control and text-direction characters as \uXXXX. Newlines and tabs stay, carriage returns go. */
    private fun clean(raw: String, max: Int): String {
        val cut = if (raw.length > max) raw.take(max) else raw
        return buildString {
            for (c in cut) {
                val control = c.isISOControl() && c != '\n' && c != '\t'
                val direction = c in '‪'..'‮' || c in '⁦'..'⁩'
                when {
                    c == '\r' -> Unit
                    control || direction -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> append(c)
                }
            }
        }
    }
}
