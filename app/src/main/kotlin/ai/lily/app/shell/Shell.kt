package ai.lily.app.shell

import ai.lily.app.ui.ChatGlyph
import ai.lily.app.ui.CountBadge
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyMark
import ai.lily.app.ui.LilyUi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The sections of the app, in the order they appear in the bar. Each is its own screen, not a dialog. */
enum class Section(val label: String, val icon: ImageVector) {
    Work("Work", Icons.Filled.Edit),
    Chat("Chat", ChatGlyph),
    Browse("Browse", Icons.Filled.Search),
    Plugins("Plugins", Icons.Filled.Build),
    Ai("AI", Icons.Filled.Star),
    Settings("Settings", Icons.Filled.Settings),
}

/** Which section is showing. */
class ShellNav {
    var section by mutableStateOf(Section.Work)
}

/** One destination: an icon on a petal pill when it is the current one, its name underneath, and a stamen count when it has one. */
@Composable
private fun NavItem(section: Section, selected: Boolean, badge: Int, onSelect: (Section) -> Unit, modifier: Modifier = Modifier) {
    val c = LilyUi.colors
    val s = MaterialTheme.colorScheme
    val glow by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "nav")
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(section) })
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.width(50.dp).height(30.dp).clip(CircleShape).background(c.petalSoft.copy(alpha = glow)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(section.icon, contentDescription = null, tint = if (selected) s.primary else c.textDim, modifier = Modifier.size(22.dp))
            if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = (-1).dp))
        }
        Text(
            section.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) s.onBackground else c.textDim,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The phone layout: a bar along the bottom, within thumb's reach. It clears the gesture area itself. */
@Composable
fun AppBottomBar(
    nav: ShellNav,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
    badge: (Section) -> Int = { 0 },
) {
    Column(modifier.fillMaxWidth().background(LilyUi.colors.raised)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (section in Section.values()) {
                NavItem(section, nav.section == section, badge(section), onSelect, Modifier.weight(1f))
            }
        }
    }
}

/** The wide-screen layout (tablets, landscape): the same destinations down the left edge, under the Lily mark. */
@Composable
fun AppSidebar(
    nav: ShellNav,
    onSelect: (Section) -> Unit,
    modifier: Modifier = Modifier,
    badge: (Section) -> Int = { 0 },
) {
    val line = LilyUi.colors.hairline
    Column(
        modifier
            .fillMaxHeight()
            .width(80.dp)
            .background(LilyUi.colors.raised)
            .drawBehind { drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LilyMark(size = 32.dp)
        Spacer(Modifier.height(12.dp))
        for (section in Section.values()) {
            NavItem(section, nav.section == section, badge(section), onSelect, Modifier.fillMaxWidth())
        }
    }
}
