package ai.lily.app.editor

import ai.lily.app.discord.AvatarId
import ai.lily.app.discord.ChannelGroup
import ai.lily.app.discord.DiscordApi
import ai.lily.app.discord.DiscordChannel
import ai.lily.app.discord.DiscordMessage
import ai.lily.app.discord.MessageFeed
import ai.lily.app.discord.MessageLayout
import ai.lily.app.discord.Snowflake
import ai.lily.app.plugins.PluginServices
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.ChoiceChip
import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyBloom
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilyComposer
import ai.lily.app.ui.LilyIconButton
import ai.lily.app.ui.LilySheet
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.StatusDot
import ai.lily.app.ui.StatusPill
import ai.lily.app.ui.Tone
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import java.text.DateFormat
import java.util.Date

/**
 * The Discord screen, shown by the Discord plugin's Open button. Servers and channels are laid out like the Discord app; the
 * messages of a channel update by themselves while the screen is open and the app is in front (live chat, by asking every few
 * seconds: Discord's push connection is not reachable from here). Tapping a picture or name opens that person's profile.
 * Everything goes through the plugin's rules in [DiscordApi]; this file only draws state from [DiscordState].
 */
@Composable
fun DiscordScreen(services: PluginServices, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state = remember { DiscordState(scope, services.discord) { services.gate.pending != null } }
    // Read again whenever the person changes a plugin switch (version goes up), so Live follows the switch at once.
    val readAutoApproved = remember(services.version) {
        services.registry.get(DiscordApi.PLUGIN_ID)?.state?.autoApprove?.contains(DiscordApi.TOOL_READ) == true
    }
    val pictures = remember(services.version) { state.avatarsAllowed() }
    val foreground = rememberInForeground()
    var showChannels by remember { mutableStateOf(true) }
    var profile by remember { mutableStateOf<DiscordMessage?>(null) }

    LaunchedEffect(Unit) { state.open() }
    val channelId = state.channel?.id
    val live = readAutoApproved && foreground && channelId != null
    LaunchedEffect(channelId, live) {
        if (live) state.runLive()
    }
    // Back closes the open channel into the channel list before it leaves the screen.
    BackHandler(enabled = state.channel != null && !showChannels) { showChannels = true }

    Column(modifier.fillMaxWidth()) {
        TopBar(state, live, readAutoApproved, showChannels, onMenu = { showChannels = !showChannels }, onRefresh = { state.refresh() }, onClose = onBack)
        Hairline()
        state.notice?.let { notice ->
            Banner(
                notice.text,
                notice.tone,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                actionLabel = if (notice.needsSetup) null else "Try again",
                onAction = if (notice.needsSetup) null else ({ state.refresh() }),
            )
        }
        if (state.channel == null || showChannels) {
            ChannelList(state, pictures, onPick = { c ->
                state.selectChannel(c)
                showChannels = false
            }, modifier = Modifier.weight(1f))
        } else {
            MessagePane(state, pictures, readAutoApproved, onProfile = { profile = it }, modifier = Modifier.weight(1f))
        }
    }

    profile?.let { m ->
        ProfileSheet(state, m, pictures, onDismiss = { profile = null })
    }
}

@Composable
private fun TopBar(
    state: DiscordState,
    live: Boolean,
    readAutoApproved: Boolean,
    showChannels: Boolean,
    onMenu: () -> Unit,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val c = LilyUi.colors
    val channel = state.channel
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LilyIconButton(Icons.Filled.Menu, if (showChannels) "Hide the channel list" else "Show servers and channels", onClick = onMenu, enabled = channel != null)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                if (channel != null) "# ${channel.name}" else (state.guild?.name ?: "Discord"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel != null) {
                    StatusDot(if (live) Tone.Pad else Tone.Neutral, Modifier.padding(end = 6.dp), size = 8)
                    Text(
                        when {
                            live -> "Live"
                            readAutoApproved -> "Paused while the app is in the background"
                            else -> "Not live: tap refresh"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textDim,
                    )
                } else {
                    Text("Your server's channels, through your own bot", style = MaterialTheme.typography.bodySmall, color = c.textDim)
                }
            }
        }
        if (state.loading) LilyBloom(size = 20.dp, modifier = Modifier.padding(end = 4.dp))
        LilyIconButton(Icons.Filled.Refresh, "Refresh", onClick = onRefresh)
        LilyIconButton(Icons.Filled.Close, "Close Discord", onClick = onClose)
    }
}

// ---- Servers and channels ----

@Composable
private fun ChannelList(state: DiscordState, pictures: Boolean, onPick: (DiscordChannel) -> Unit, modifier: Modifier) {
    val c = LilyUi.colors
    if (state.guilds.isEmpty() && !state.loading && state.notice == null) {
        EmptyState("No servers", "The bot is not in a server.", modifier)
        return
    }
    LazyColumn(modifier.fillMaxWidth()) {
        if (state.guilds.size > 1) {
            item {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.guilds.size) { i ->
                        val g = state.guilds[i]
                        ChoiceChip(g.name, selected = g.id == state.guild?.id, onClick = { state.selectGuild(g) })
                    }
                }
            }
        }
        if (state.guild == null && state.guilds.size > 1) {
            item { Text("Choose a server.", style = MaterialTheme.typography.bodyMedium, color = c.textDim, modifier = Modifier.padding(horizontal = 20.dp)) }
        }
        for (group in state.groups) {
            item(key = "cat-" + (group.category?.id ?: "none")) { CategoryHeader(group) }
            items(group.channels.size, key = { i -> "ch-" + group.channels[i].id }) { i ->
                ChannelRow(group.channels[i], selected = group.channels[i].id == state.channel?.id, onPick = onPick)
            }
        }
        if (!pictures && state.groups.isNotEmpty()) {
            item {
                Banner(
                    "Profile pictures are off. To see them, allow cdn.discordapp.com/avatars/ on the Discord plugin's page in Plugins. Initials are shown meanwhile.",
                    Tone.Neutral,
                    Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(group: ChannelGroup) {
    val name = group.category?.name?.uppercase() ?: return
    Text(
        name,
        style = MaterialTheme.typography.labelMedium,
        color = LilyUi.colors.textDim,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChannelRow(channel: DiscordChannel, selected: Boolean, onPick: (DiscordChannel) -> Unit) {
    val c = LilyUi.colors
    val prefix = when {
        channel.readable -> "#"
        channel.isVoiceLike -> "🔊"
        else -> "▫"
    }
    val note = if (channel.readable) "" else if (channel.isVoiceLike) "  (voice)" else "  (not shown here)"
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (selected) c.petalSoft else Color.Transparent)
            .then(if (channel.readable) Modifier.clickable(role = Role.Button, onClick = { onPick(channel) }) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$prefix ${channel.name}$note",
            style = MaterialTheme.typography.bodyLarge,
            color = if (channel.readable) MaterialTheme.colorScheme.onBackground else c.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- Messages ----

@Composable
private fun MessagePane(state: DiscordState, pictures: Boolean, readAutoApproved: Boolean, onProfile: (DiscordMessage) -> Unit, modifier: Modifier) {
    val channel = state.channel ?: return
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    var unseen by remember { mutableIntStateOf(0) }
    val count = state.messages.size
    val older = if (!state.atStart && count > 0) 1 else 0
    val atBottom by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }

    // A newly opened channel starts at its newest message.
    LaunchedEffect(channel.id, state.loading) {
        if (!state.loading && count > 0) list.scrollToItem(count - 1 + older)
    }
    // New messages follow along only if the person was already at the bottom; otherwise a chip says how many came.
    val newest = state.messages.lastOrNull()?.id
    LaunchedEffect(newest) {
        if (newest == null || state.loading) return@LaunchedEffect
        if (atBottom) list.animateScrollToItem(count - 1 + older) else unseen++
    }
    LaunchedEffect(atBottom) { if (atBottom) unseen = 0 }

    Column(modifier.fillMaxWidth()) {
        if (!readAutoApproved) {
            Banner(
                "Live updates are off. Switch on \"Run without asking each time\" for discord_read_messages on the plugin's page, or tap refresh (each refresh asks first).",
                Tone.Neutral,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (MessageFeed.contentMayBeHidden(state.messages)) {
            Banner(
                "These messages have no text. Turn on Message Content Intent for the bot in the developer portal (Bot page), or the bot cannot read what people wrote.",
                Tone.Stamen,
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (count == 0 && !state.loading) {
                EmptyState("No messages", "Nothing has been said in this channel yet, or the bot may not read its history.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    if (older == 1) {
                        item(key = "older") {
                            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                                LilyButton("Load older messages", onClick = { state.loadOlder() }, kind = ButtonKind.Quiet, enabled = !state.loading)
                            }
                        }
                    }
                    itemsIndexed(state.messages, key = { _, m -> m.id }) { i, m ->
                        val previous = state.messages.getOrNull(i - 1)
                        MessageItem(state, m, MessageLayout.startsGroup(previous, m), pictures, onProfile)
                    }
                }
            }
            if (!atBottom && unseen > 0) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                    LilyButton(
                        if (unseen == 1) "1 new message" else "$unseen new messages",
                        onClick = { scope.launch2 { list.animateScrollToItem(count - 1 + older); unseen = 0 } },
                        kind = ButtonKind.Primary,
                    )
                }
            }
        }
        Hairline()
        LilyComposer(
            value = text,
            onValueChange = { text = it },
            hint = "Message # ${channel.name} (asks first)",
            sendLabel = "Send",
            onSend = { scope.launch2 { if (state.send(text)) text = "" } },
            enabled = !state.sending,
        )
    }
}

/** [kotlinx.coroutines.launch] without importing it twice into a file that already reads long. */
private fun kotlinx.coroutines.CoroutineScope.launch2(block: suspend () -> Unit) {
    this.launch { block() }
}

@Composable
private fun MessageItem(state: DiscordState, m: DiscordMessage, startsGroup: Boolean, pictures: Boolean, onProfile: (DiscordMessage) -> Unit) {
    val c = LilyUi.colors
    if (startsGroup && pictures) {
        LaunchedEffect(m.authorId, m.authorAvatar) { state.wantAvatar(m.authorId, m.authorAvatar, AvatarId.SMALL) }
    }
    if (!m.isPlain) {
        Text(
            "— " + m.content.ifEmpty { "system message" },
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = c.textFaint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = if (startsGroup) 12.dp else 1.dp)) {
        Box(Modifier.width(48.dp)) {
            if (startsGroup) {
                val bytes = m.authorAvatar?.let { state.avatars[AvatarId.key(m.authorId, it, AvatarId.SMALL)] }
                Avatar(
                    m.authorName,
                    m.authorId,
                    bytes,
                    40.dp,
                    Modifier.clickable(role = Role.Button, onClickLabel = "Open ${m.authorName}'s profile") { onProfile(m) },
                )
            }
        }
        Column(Modifier.weight(1f)) {
            if (startsGroup) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        m.authorName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable(role = Role.Button, onClickLabel = "Open profile") { onProfile(m) },
                    )
                    if (m.authorIsBot) StatusPill("BOT", Tone.Petal, Modifier.padding(start = 6.dp))
                    Text(whenText(m.id), style = MaterialTheme.typography.bodySmall, color = c.textDim, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (m.content.isNotEmpty()) {
                SelectionContainer {
                    Text(m.content, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                }
            }
            for (file in m.attachments) {
                Text("📎 $file", style = MaterialTheme.typography.bodyMedium, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (m.embedCount > 0) {
                Text(if (m.embedCount == 1) "1 embed (not shown)" else "${m.embedCount} embeds (not shown)", style = MaterialTheme.typography.bodySmall, color = c.textFaint)
            }
            if (m.editedTimestamp != null) {
                Text("edited", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
            }
        }
    }
}

// ---- Avatar and profile ----

/** A round picture, or the person's first letter on a colour taken from their id when there is no picture. */
@Composable
private fun Avatar(name: String, id: String, bytes: ByteArray?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()?.asImageBitmap() }
    }
    val tint = remember(id, name) { Color.hsv(((id.ifEmpty { name }.hashCode() and 0x7fffffff) % 360).toFloat(), 0.45f, 0.5f) }
    Box(modifier.size(size).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            Text(initial, color = Color.White, fontSize = (size.value * 0.42f).sp, maxLines = 1)
        }
    }
}

/** One person as Discord shows them to a bot: their picture, names, id and when the account was made, plus what they said here. */
@Composable
private fun ProfileSheet(state: DiscordState, m: DiscordMessage, pictures: Boolean, onDismiss: () -> Unit) {
    val c = LilyUi.colors
    LaunchedEffect(m.authorId, m.authorAvatar, pictures) {
        if (pictures) {
            state.wantAvatar(m.authorId, m.authorAvatar, AvatarId.LARGE)
            state.wantAvatar(m.authorId, m.authorAvatar, AvatarId.SMALL)
        }
    }
    val big = m.authorAvatar?.let { hash ->
        state.avatars[AvatarId.key(m.authorId, hash, AvatarId.LARGE)] ?: state.avatars[AvatarId.key(m.authorId, hash, AvatarId.SMALL)]
    }
    val theirs = state.messages.filter { it.authorId == m.authorId && m.authorId.isNotEmpty() }
    LilySheet("Profile", onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.authorName, m.authorId, big, 96.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(m.authorName, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                if (m.authorUsername.isNotEmpty() && m.authorUsername != m.authorName) {
                    Text("@${m.authorUsername}", style = MaterialTheme.typography.bodyMedium, color = c.textDim)
                }
                if (m.authorIsBot) StatusPill("BOT", Tone.Petal, Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.size(16.dp))
        ProfileLine("User id", m.authorId.ifEmpty { "unknown" }, mono = true)
        Snowflake.createdAtMillis(m.authorId)?.let { ProfileLine("Account made", DateFormat.getDateInstance(DateFormat.LONG).format(Date(it))) }
        ProfileLine("Messages loaded here", theirs.size.toString())
        theirs.lastOrNull()?.let { ProfileLine("Last message", whenText(it.id)) }
        if (m.authorAvatar == null) {
            Text("This person uses a default picture, so initials are shown.", style = MaterialTheme.typography.bodySmall, color = c.textDim, modifier = Modifier.padding(top = 12.dp))
        } else if (!pictures) {
            Banner(
                "Pictures are off. Allow cdn.discordapp.com/avatars/ on the Discord plugin's page in Plugins to see this one.",
                Tone.Neutral,
                Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun ProfileLine(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LilyUi.colors.textDim)
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = if (mono) FontFamily.Monospace else MaterialTheme.typography.bodyLarge.fontFamily),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/** The time a message was sent (from its id), as a clock time today and a date and time before that, in the phone's own format. */
private fun whenText(messageId: String): String {
    val at = Snowflake.createdAtMillis(messageId) ?: return ""
    val ageMs = System.currentTimeMillis() - at
    val date = Date(at)
    return if (ageMs in 0..DAY_MS) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    } else {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(date)
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

// ---- Foreground ----

private fun Context.lifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/** True while the app is in front. Live polling stops in the background so the phone is not asked to fetch with the screen off. */
@Composable
private fun rememberInForeground(): Boolean {
    val owner = LocalContext.current.lifecycleOwner()
    var foreground by remember { mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    return foreground
}
