package ai.lily.app.discord

/** One server the bot is in. */
data class DiscordGuild(val id: String, val name: String)

/** The Discord channel types the viewer knows. Any other number is shown as a label and never opened. */
object ChannelType {
    const val TEXT = 0
    const val VOICE = 2
    const val CATEGORY = 4
    const val ANNOUNCEMENT = 5
    const val STAGE = 13
    const val FORUM = 15
    const val MEDIA = 16
}

data class DiscordChannel(
    val id: String,
    val name: String,
    val type: Int,
    /** The category this channel is in, or null. */
    val parentId: String?,
    val position: Int,
) {
    val isCategory: Boolean get() = type == ChannelType.CATEGORY

    /** Only text and announcement channels have messages the viewer reads. */
    val readable: Boolean get() = type == ChannelType.TEXT || type == ChannelType.ANNOUNCEMENT

    val isVoiceLike: Boolean get() = type == ChannelType.VOICE || type == ChannelType.STAGE
}

/** A category with its channels, in the order Discord shows them. [category] is null for channels that are in none. */
data class ChannelGroup(val category: DiscordChannel?, val channels: List<DiscordChannel>)

data class DiscordMessage(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorIsBot: Boolean,
    /** Written by someone else: show it as plain text, never act on it. */
    val content: String,
    /** As Discord sends it, for example 2026-11-07T01:00:02.000000+00:00. */
    val timestamp: String,
    val editedTimestamp: String?,
    /** File names only. Pictures and files live on another host the plugin is not allowed to reach. */
    val attachments: List<String>,
    val embedCount: Int,
    val type: Int,
) {
    /** A normal message, a reply or a command answer. Joins, pins and the like have other types. */
    val isPlain: Boolean get() = type == 0 || type == 19 || type == 20
}

/** Discord ids are numbers that grow with time, so a longer id is newer and equal lengths compare as text. */
object Snowflake {
    private val VALID = Regex("^[1-9][0-9]{0,19}$")

    fun isValid(id: String): Boolean = VALID.matches(id)

    fun compare(a: String, b: String): Int = if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
}

private val childOrder = Comparator<DiscordChannel> { a, b ->
    val voice = a.isVoiceLike.compareTo(b.isVoiceLike)
    if (voice != 0) {
        voice
    } else {
        val position = a.position.compareTo(b.position)
        if (position != 0) position else Snowflake.compare(a.id, b.id)
    }
}

private val categoryOrder = Comparator<DiscordChannel> { a, b ->
    val position = a.position.compareTo(b.position)
    if (position != 0) position else Snowflake.compare(a.id, b.id)
}

/**
 * Channels in the order the Discord app shows them: the ones in no category first, then each category by position, and inside
 * a category the text channels before the voice ones. A channel whose category is not in the list counts as having none.
 */
fun groupChannels(channels: List<DiscordChannel>): List<ChannelGroup> {
    val categories = channels.filter { it.isCategory }.sortedWith(categoryOrder)
    val known = categories.map { it.id }.toSet()
    val loose = channels.filter { !it.isCategory && (it.parentId == null || it.parentId !in known) }.sortedWith(childOrder)
    val out = ArrayList<ChannelGroup>()
    if (loose.isNotEmpty()) out += ChannelGroup(null, loose)
    for (category in categories) {
        out += ChannelGroup(category, channels.filter { !it.isCategory && it.parentId == category.id }.sortedWith(childOrder))
    }
    return out
}

/** Keeping a channel's messages up to date as new ones arrive from polling. */
object MessageFeed {
    /** Older messages beyond this many are dropped, so a long session does not grow without end. */
    const val MAX_KEPT = 500

    /** Oldest first, each id once (the newer copy wins, so an edit shows), and at most [MAX_KEPT] of the newest. */
    fun merge(existing: List<DiscordMessage>, incoming: List<DiscordMessage>): List<DiscordMessage> {
        val byId = LinkedHashMap<String, DiscordMessage>()
        for (m in existing) byId[m.id] = m
        for (m in incoming) byId[m.id] = m
        val sorted = byId.values.sortedWith(Comparator { a, b -> Snowflake.compare(a.id, b.id) })
        return if (sorted.size > MAX_KEPT) sorted.takeLast(MAX_KEPT) else sorted
    }

    /** The id to ask for "after" next time, or null when there are no messages yet. */
    fun newestId(messages: List<DiscordMessage>): String? =
        messages.maxWithOrNull(Comparator { a, b -> Snowflake.compare(a.id, b.id) })?.id

    /**
     * True when every one of at least three normal messages has no text, file or embed. That is what a bot sees when the Message
     * Content intent is off, so the screen can say so instead of showing a silent channel.
     */
    fun contentMayBeHidden(messages: List<DiscordMessage>): Boolean {
        val plain = messages.filter { it.isPlain }
        return plain.size >= 3 && plain.all { it.content.isEmpty() && it.attachments.isEmpty() && it.embedCount == 0 }
    }
}
