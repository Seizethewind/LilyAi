package ai.lily.app.shell

import ai.lily.app.core.WorkerType
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.Tone
import ai.lily.app.ui.ink
import ai.lily.app.ui.wash
import ai.lily.app.workers.WorkerLook
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** The Material icon for a [WorkerLook] key. Unknown keys cannot reach here, because [WorkerLook.icon] already falls back. */
private fun workerIcon(key: String): ImageVector = when (key) {
    "build" -> Icons.Filled.Build
    "search" -> Icons.Filled.Search
    "share" -> Icons.Filled.Share
    "star" -> Icons.Filled.Star
    "favorite" -> Icons.Filled.Favorite
    "home" -> Icons.Filled.Home
    "settings" -> Icons.Filled.Settings
    "info" -> Icons.Filled.Info
    "check" -> Icons.Filled.Check
    "edit" -> Icons.Filled.Edit
    else -> Icons.Filled.Person
}

private fun workerTone(key: String): Tone = when (key) {
    "petal" -> Tone.Petal
    "pad" -> Tone.Pad
    "stamen" -> Tone.Stamen
    "coral" -> Tone.Coral
    else -> Tone.Neutral
}

private fun label(key: String) = key.replaceFirstChar { it.uppercase() }

/** A worker's icon on a round tinted disc, the way it shows on its row. */
@Composable
fun WorkerBadge(role: WorkerType, iconKey: String, colorKey: String, modifier: Modifier = Modifier) {
    val tone = workerTone(WorkerLook.color(role, colorKey))
    Box(modifier.size(40.dp).clip(CircleShape).background(tone.wash()), contentAlignment = Alignment.Center) {
        Icon(workerIcon(WorkerLook.icon(role, iconKey)), contentDescription = null, tint = tone.ink(), modifier = Modifier.size(22.dp))
    }
}

/**
 * The icon grid and colour swatches. Each one that is chosen is stored by its key; one that was never touched stays blank, so
 * the worker keeps following its role's look when the role changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookPicker(role: WorkerType, iconKey: String, colorKey: String, onIcon: (String) -> Unit, onColor: (String) -> Unit) {
    val c = LilyUi.colors
    val primary = MaterialTheme.colorScheme.primary
    val shownIcon = WorkerLook.icon(role, iconKey)
    val shownColor = WorkerLook.color(role, colorKey)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Icon", style = MaterialTheme.typography.labelMedium, color = c.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in WorkerLook.icons) {
                    val on = key == shownIcon
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (on) c.petalSoft else c.sunken)
                            .border(1.dp, if (on) primary else c.hairline, CircleShape)
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onIcon(key) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(workerIcon(key), contentDescription = label(key), tint = if (on) primary else c.textDim, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Colour", style = MaterialTheme.typography.labelMedium, color = c.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in WorkerLook.colors) {
                    val on = key == shownColor
                    val tone = workerTone(key)
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .border(if (on) 2.dp else 1.dp, if (on) primary else c.hairline, CircleShape)
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onColor(key) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The swatch has no text, so a screen reader needs the name on the disc itself.
                        Box(Modifier.size(28.dp).clip(CircleShape).background(tone.ink()))
                        Text(label(key), modifier = Modifier.size(0.dp), color = tone.ink())
                    }
                }
            }
        }
    }
}
