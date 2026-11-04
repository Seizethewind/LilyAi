package ai.lily.app.shell

import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.LilyIconButton
import ai.lily.app.ui.LilyMark
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ScreenFrame
import ai.lily.app.ui.ScreenHeader
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop

/** The four pages of the AI section, in the order the drawer lists them. */
enum class AiPage(val label: String, val icon: ImageVector) {
    Lily("Lily", Icons.Filled.Person),
    Workers("Workers", Icons.Filled.Build),
    Routers("Router config", Icons.Filled.Share),
    Models("Model list", Icons.Filled.Search),
}

/** Which AI page shows and whether its drawer is open. Kept outside the screen so leaving the AI tab does not forget either. */
class AiNav {
    var page by mutableStateOf(AiPage.Lily)
    var menuOpen by mutableStateOf(false)
}

/**
 * The AI section: a drawer listing its pages, and the chosen page beside it. Tapping the AI tab opens the drawer. It is opened by
 * tap only, because a swipe from the left edge is also Android's back gesture. Wide screens show the drawer all the time.
 */
@Composable
fun AiHost(state: AiState, nav: AiNav, modifier: Modifier = Modifier) {
    val permanent = LocalConfiguration.current.screenWidthDp >= 840
    val pick: (AiPage) -> Unit = {
        nav.page = it
        nav.menuOpen = false
    }
    if (permanent) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(Modifier.width(280.dp), drawerContainerColor = LilyUi.colors.raised) { AiDrawerBody(state, nav.page, pick) }
            },
            modifier = modifier,
        ) { AiPageBody(state, nav.page, onMenu = null) }
    } else {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        LaunchedEffect(nav.menuOpen) { if (nav.menuOpen) drawer.open() else drawer.close() }
        // The scrim closes the drawer by itself, so the flag follows the drawer back. drop(1) skips the starting value.
        LaunchedEffect(drawer) { snapshotFlow { drawer.currentValue }.drop(1).collect { nav.menuOpen = it == DrawerValue.Open } }
        BackHandler(enabled = nav.menuOpen) { nav.menuOpen = false }
        ModalNavigationDrawer(
            drawerContent = {
                ModalDrawerSheet(drawerContainerColor = LilyUi.colors.raised) { AiDrawerBody(state, nav.page, pick) }
            },
            modifier = modifier,
            drawerState = drawer,
            gesturesEnabled = false,
            scrimColor = Color.Black.copy(alpha = 0.5f),
        ) { AiPageBody(state, nav.page, onMenu = { nav.menuOpen = true }) }
    }
}

@Composable
private fun AiPageBody(state: AiState, page: AiPage, onMenu: (() -> Unit)?) {
    when (page) {
        AiPage.Workers, AiPage.Routers -> AiScreen(state, page, onMenu)
        AiPage.Lily, AiPage.Models -> SoonPage(page, onMenu)
    }
}

/** Stands in for a page that is built in a later step, so the drawer already lists all four. */
@Composable
private fun SoonPage(page: AiPage, onMenu: (() -> Unit)?) {
    ScreenFrame {
        ScreenHeader(page.label, actions = { if (onMenu != null) LilyIconButton(Icons.Filled.Menu, "Open AI menu", onMenu) })
        EmptyState(title = "Coming next", message = "This page arrives in the next step of the AI update.")
    }
}

@Composable
private fun AiDrawerBody(state: AiState, current: AiPage, onPick: (AiPage) -> Unit) {
    val settings = state.settings
    val loaded = state.models.values.sumOf { it.size }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LilyMark(size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Lily", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Text("AI", style = MaterialTheme.typography.bodySmall, color = LilyUi.colors.textDim)
            }
        }
        for (page in AiPage.values()) {
            val subtitle = when (page) {
                AiPage.Lily -> "Profile and instructions"
                AiPage.Workers -> if (settings.workers.isEmpty()) "Built-in workers" else "${settings.workers.size} created"
                AiPage.Routers -> if (settings.router2 != null) "2 routers" else "1 router"
                AiPage.Models -> if (loaded == 0) "Not loaded yet" else "$loaded models"
            }
            DrawerItem(page, subtitle, selected = page == current, onClick = { onPick(page) })
        }
    }
}

@Composable
private fun DrawerItem(page: AiPage, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val c = LilyUi.colors
    val s = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(if (selected) c.petalSoft else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(page.icon, contentDescription = null, tint = if (selected) s.primary else c.textDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(page.label, style = MaterialTheme.typography.titleSmall, color = if (selected) s.primary else s.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
