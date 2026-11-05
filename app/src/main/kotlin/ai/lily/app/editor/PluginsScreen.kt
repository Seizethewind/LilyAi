package ai.lily.app.editor

import ai.lily.app.plugins.CatalogEntry
import ai.lily.app.plugins.InstalledPlugin
import ai.lily.app.plugins.Listing
import ai.lily.app.plugins.ManifestParser
import ai.lily.app.plugins.PluginCatalog
import ai.lily.app.plugins.PluginServices
import ai.lily.app.plugins.mergeListings
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.ConfirmDialog
import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.LilyBloom
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilySwitch
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ScreenFrame
import ai.lily.app.ui.ScreenHeader
import ai.lily.app.ui.SegmentedTabs
import ai.lily.app.ui.TabSpec
import ai.lily.app.ui.Tone
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The Plugins section. Installed shows what the person has; All adds what ships in the app and what the catalog offers, so a
 * removed plugin can be put back. A card installs or uninstalls in one tap; tapping the card opens its page.
 */
@Composable
fun PluginsScreen(services: PluginServices, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val installed = remember(services.version) { services.registry.list() }
    val bundled = remember { services.bundled() }
    var catalog by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Listing?>(null) }

    // shortcut: the catalog is fetched again every time this section opens; cache it if that gets slow.
    fun loadCatalog() {
        scope.launch {
            loading = true
            problem = null
            try {
                catalog = PluginCatalog.loadIndex(PluginCatalog.DEFAULT_URL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "Could not load the catalog: ${e.message ?: e::class.java.simpleName}"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { loadCatalog() }

    val all = remember(installed, bundled, catalog) { mergeListings(installed, bundled, catalog) }
    val open = openId?.let { id -> all.firstOrNull { it.id == id } }

    fun install(l: Listing) {
        if (installing != null) return
        scope.launch {
            installing = l.id
            try {
                note = when {
                    l.bundledText != null -> installMessage(l.name, services.install(l.bundledText))
                    l.manifestUrl != null -> installFromCatalog(services, l.id, l.name, l.manifestUrl)
                    else -> null
                }
            } finally {
                installing = null
            }
        }
    }

    ScreenFrame(modifier) {
        note?.let { message ->
            Banner(message, Tone.Petal, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), actionLabel = "Dismiss", onAction = { note = null })
        }
        if (open != null) {
            PluginDetail(
                listing = open,
                plugin = installed.firstOrNull { it.manifest.id == open.id },
                services = services,
                busy = installing == open.id,
                onBack = { openId = null },
                onInstall = { install(open) },
                onUninstall = { removing = open },
                modifier = Modifier.weight(1f),
            )
        } else {
            val shown = all.filter { (tab == 1 || it.installed) && it.matches(query) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                item {
                    ScreenHeader(
                        "Plugins",
                        subtitle = "A plugin is a plain manifest. Nothing runs until you turn it on and allow it.",
                        actions = { LilyButton("Add from JSON", onClick = { adding = true }, kind = ButtonKind.Quiet) },
                    )
                }
                item { SegmentedTabs(listOf(TabSpec("Installed"), TabSpec("All")), tab, { tab = it }) }
                item {
                    LilyTextField(
                        query,
                        { query = it },
                        "Search",
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        placeholder = "Name, author or what it does",
                    )
                }
                if (tab == 1) {
                    problem?.let { message ->
                        item {
                            Banner(message, Tone.Coral, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), actionLabel = "Retry", onAction = { loadCatalog() })
                        }
                    }
                    if (loading) {
                        item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { LilyBloom(size = 24.dp) } }
                    }
                }
                if (shown.isEmpty() && !loading) {
                    item {
                        if (tab == 0 && query.isBlank()) {
                            EmptyState(
                                title = "No plugins installed",
                                message = "Open All to find one, or paste a manifest you trust.",
                                actionLabel = "Browse all",
                                onAction = { tab = 1 },
                            )
                        } else {
                            EmptyState(
                                title = "Nothing matches",
                                message = if (tab == 0) "No installed plugin matches that search. Try All." else "Try another word.",
                            )
                        }
                    }
                }
                items(shown, key = { l -> l.id }) { l ->
                    PluginCard(
                        listing = l,
                        plugin = installed.firstOrNull { it.manifest.id == l.id },
                        busy = installing == l.id,
                        onOpen = { openId = l.id },
                        onToggle = { on -> services.setEnabled(l.id, on) },
                        onInstall = { install(l) },
                        onUninstall = { removing = l },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    removing?.let { l ->
        ConfirmDialog(
            title = "Uninstall ${l.name}?",
            message = "Its settings and allowances are deleted. You can add it again later.",
            confirmLabel = "Uninstall",
            danger = true,
            onConfirm = {
                services.uninstall(l.id)
                note = "${l.name} uninstalled"
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
    if (adding) {
        AddPluginSheet(services, onDismiss = { adding = false }) { message ->
            note = message
            adding = false
        }
    }
}

/** "v1.0.0 · Someone", leaving out what the plugin does not say. */
private fun byline(l: Listing): String = listOf("v${l.version}", l.author).filter { it.length > 1 }.joinToString(" \u00B7 ")

/** The plugin's icon, or its first letter, on a tile in its accent colour (the app's petal colour when it has none). */
@Composable
private fun IconTile(l: Listing, size: Dp) {
    val accent = if (ManifestParser.ACCENT.matches(l.accent)) Color(0xFF000000L or l.accent.drop(1).toLong(16)) else MaterialTheme.colorScheme.primary
    val ink = if (accent.luminance() > 0.5f) Color.Black else Color.White
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(accent), contentAlignment = Alignment.Center) {
        Text(l.tile, color = ink, fontSize = (size.value * 0.42f).sp, maxLines = 1)
    }
}

@Composable
private fun PluginCard(
    listing: Listing,
    plugin: InstalledPlugin?,
    busy: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val c = LilyUi.colors
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.hairline, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(listing, 56.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(listing.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(byline(listing), style = MaterialTheme.typography.bodySmall, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (plugin != null) LilySwitch(plugin.state.enabled, onToggle)
        }
        if (listing.description.isNotEmpty()) {
            Text(listing.description, style = MaterialTheme.typography.bodyMedium, color = c.textDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (listing.installed) {
            LilyButton("Uninstall", onUninstall, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        } else {
            LilyButton(if (busy) "Installing" else "Install", onInstall, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

/** One plugin's page. Installed: what it can do and what it is allowed to. Not installed: the card's facts and an Install button. */
@Composable
private fun PluginDetail(
    listing: Listing,
    plugin: InstalledPlugin?,
    services: PluginServices,
    busy: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val c = LilyUi.colors
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LilyButton("Back", onBack, kind = ButtonKind.Quiet)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(listing, 72.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(listing.name, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(byline(listing), style = MaterialTheme.typography.bodyMedium, color = c.textDim)
            }
        }
        if (listing.description.isNotEmpty()) {
            Text(listing.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        }
        if (plugin != null) {
            PluginDetailBody(plugin, services)
            LilyButton("Uninstall", onUninstall, Modifier.fillMaxWidth(), kind = ButtonKind.Danger)
        } else {
            LilyButton(if (busy) "Installing" else "Install", onInstall, Modifier.fillMaxWidth(), enabled = !busy)
            Banner("Installing adds it turned off with nothing allowed. What it can do and ask for shows here afterwards.", Tone.Neutral)
        }
    }
}
