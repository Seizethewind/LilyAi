package ai.lily.app.editor

import ai.lily.app.discord.AvatarId
import ai.lily.app.discord.ChannelGroup
import ai.lily.app.discord.DiscordApi
import ai.lily.app.discord.DiscordChannel
import ai.lily.app.discord.DiscordGuild
import ai.lily.app.discord.DiscordMessage
import ai.lily.app.discord.DiscordResult
import ai.lily.app.discord.LiveChat
import ai.lily.app.discord.MessageFeed
import ai.lily.app.discord.groupChannels
import ai.lily.app.ui.Tone
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Something to tell the person above the messages. [needsSetup] means the fix is on the plugin's page, not a retry. */
class DiscordNotice(val text: String, val tone: Tone, val needsSetup: Boolean = false)

/**
 * What the Discord screen shows and does: the servers, the channels of one, the messages of one channel, pictures, and the live
 * polling. All calls go through [DiscordApi], so the plugin's switches and questions apply to every one of them.
 * A change of server or channel starts a new [epoch]; an answer that arrives for an older one is dropped, so a slow reply
 * never lands in the wrong channel.
 */
class DiscordState(
    private val scope: CoroutineScope,
    private val api: DiscordApi,
    /** True while a question to the person is on screen: polling waits instead of piling up more questions. */
    private val promptOpen: () -> Boolean,
) {
    var guilds by mutableStateOf<List<DiscordGuild>>(emptyList())
        private set
    var guild by mutableStateOf<DiscordGuild?>(null)
        private set
    var groups by mutableStateOf<List<ChannelGroup>>(emptyList())
        private set
    var channel by mutableStateOf<DiscordChannel?>(null)
        private set
    var messages by mutableStateOf<List<DiscordMessage>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set

    /** True once the oldest message of the channel has been loaded. */
    var atStart by mutableStateOf(false)
        private set
    var notice by mutableStateOf<DiscordNotice?>(null)
        private set

    /** Pictures that arrived, by [AvatarId.key]. A missing key means not loaded (or not allowed, or gone). */
    val avatars = mutableStateMapOf<String, ByteArray>()

    private var epoch = 0
    private val tried = HashSet<String>()
    private val slots = Semaphore(MAX_PICTURE_REQUESTS)

    fun avatarsAllowed(): Boolean = api.avatarsAllowed()

    /** Loads the servers once when the screen opens. */
    fun open() {
        if (guilds.isEmpty() && !loading) loadGuilds()
    }

    /** Tries again at whatever level failed: the messages if a channel is open, else the channels, else the servers. */
    fun refresh() {
        tried.clear()
        val c = channel
        val g = guild
        when {
            c != null -> loadLatest(c)
            g != null -> selectGuild(g)
            else -> loadGuilds()
        }
    }

    private fun loadGuilds() {
        scope.launch {
            loading = true
            when (val r = api.guilds()) {
                is DiscordResult.Ok -> {
                    guilds = r.value
                    notice = if (r.value.isEmpty()) {
                        DiscordNotice("The bot is not in any server yet. Invite it with the link from the developer portal.", Tone.Neutral)
                    } else {
                        null
                    }
                    if (r.value.size == 1 && guild == null) selectGuild(r.value[0])
                }
                is DiscordResult.Failed -> fail(r)
            }
            loading = false
        }
    }

    fun selectGuild(g: DiscordGuild) {
        val mine = ++epoch
        guild = g
        channel = null
        messages = emptyList()
        groups = emptyList()
        scope.launch {
            loading = true
            when (val r = api.channels(g.id)) {
                is DiscordResult.Ok -> if (mine == epoch) {
                    groups = groupChannels(r.value)
                    notice = if (r.value.isEmpty()) DiscordNotice("The bot sees no channels here. Check its role in the server.", Tone.Neutral) else null
                }
                is DiscordResult.Failed -> if (mine == epoch) fail(r)
            }
            if (mine == epoch) loading = false
        }
    }

    fun selectChannel(c: DiscordChannel) {
        if (!c.readable) return
        ++epoch
        channel = c
        messages = emptyList()
        atStart = false
        loadLatest(c)
    }

    /** Shows the newest messages (replacing what was there, so an edit or a deleted message is brought up to date). */
    private fun loadLatest(c: DiscordChannel) {
        val mine = epoch
        scope.launch {
            loading = true
            when (val r = api.messages(c.id)) {
                is DiscordResult.Ok -> if (mine == epoch) {
                    messages = MessageFeed.merge(emptyList(), r.value)
                    atStart = r.value.size < PAGE
                    notice = null
                }
                is DiscordResult.Failed -> if (mine == epoch) fail(r)
            }
            if (mine == epoch) loading = false
        }
    }

    /** Adds the page of messages before the oldest one shown. */
    fun loadOlder() {
        val c = channel ?: return
        val oldest = messages.firstOrNull()?.id ?: return
        if (loading || atStart) return
        val mine = epoch
        scope.launch {
            loading = true
            when (val r = api.messages(c.id, limit = PAGE, before = oldest)) {
                is DiscordResult.Ok -> if (mine == epoch) {
                    messages = MessageFeed.merge(messages, r.value)
                    if (r.value.size < PAGE) atStart = true
                }
                is DiscordResult.Failed -> if (mine == epoch) fail(r)
            }
            if (mine == epoch) loading = false
        }
    }

    /** Posts a message. The plugin asks the person first. True when it was posted (so the box can be emptied). */
    suspend fun send(text: String): Boolean {
        val c = channel ?: return false
        if (sending) return false
        sending = true
        try {
            return when (val r = api.send(c.id, text.trimEnd())) {
                is DiscordResult.Ok -> {
                    if (channel?.id == c.id) messages = MessageFeed.merge(messages, listOf(r.value))
                    notice = null
                    true
                }
                is DiscordResult.Failed -> {
                    notice = if (r.denied) DiscordNotice(r.message, Tone.Neutral) else toNotice(r)
                    false
                }
            }
        } finally {
            sending = false
        }
    }

    /**
     * Keeps the open channel up to date by asking for the messages after the newest one, every few seconds, until the caller
     * cancels it (the screen closes, the app goes to the background, another channel opens). Waits while a question is on
     * screen, slows down when nothing happens, obeys Discord's slow-down, and stops for a failure only the person can fix.
     * The caller starts it only when reads are set to not ask each time, otherwise every poll would ask.
     */
    suspend fun runLive() {
        val c = channel ?: return
        val mine = epoch
        var idle = 0
        var hint: Long? = null
        while (true) {
            delay(LiveChat.delayMs(idle, hint))
            hint = null
            if (mine != epoch) return
            if (promptOpen() || loading) continue
            when (val r = api.messages(c.id, limit = POLL_PAGE, after = MessageFeed.newestId(messages))) {
                is DiscordResult.Ok -> {
                    if (mine != epoch) return
                    if (r.value.isEmpty()) {
                        idle++
                    } else {
                        idle = 0
                        messages = MessageFeed.merge(messages, r.value)
                    }
                    if (notice?.tone == Tone.Coral) notice = null
                }
                is DiscordResult.Failed -> {
                    if (mine != epoch) return
                    fail(r)
                    if (r.needsSetup || r.denied) return
                    hint = r.retryAfterMs
                    idle++
                }
            }
        }
    }

    /** Starts loading a picture if it may be loaded and has not been tried. Never asks and never fails loudly: initials stay. */
    fun wantAvatar(userId: String, hash: String?, size: Int = AvatarId.SMALL) {
        if (hash == null || userId.isEmpty() || !api.avatarsAllowed()) return
        val key = AvatarId.key(userId, hash, size)
        if (key in avatars || !tried.add(key)) return
        scope.launch {
            slots.withPermit {
                val r = api.avatar(userId, hash, size)
                if (r is DiscordResult.Ok) {
                    if (avatars.size >= MAX_PICTURES) avatars.keys.firstOrNull()?.let { avatars.remove(it) }
                    avatars[key] = r.value
                }
            }
        }
    }

    private fun fail(r: DiscordResult.Failed) {
        notice = toNotice(r)
    }

    private fun toNotice(r: DiscordResult.Failed) = DiscordNotice(r.message, if (r.needsSetup) Tone.Stamen else Tone.Coral, r.needsSetup)

    private companion object {
        const val PAGE = 50
        const val POLL_PAGE = 100
        const val MAX_PICTURE_REQUESTS = 3
        const val MAX_PICTURES = 300
    }
}
