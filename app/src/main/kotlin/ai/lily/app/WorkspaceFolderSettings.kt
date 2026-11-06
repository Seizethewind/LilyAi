package ai.lily.app

import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.SectionTitle
import ai.lily.app.ui.Tone
import ai.lily.app.workspace.WorkspaceLocation
import ai.lily.app.workspace.WorkspaceSetup
import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * The Settings group where the person chooses the folder the project files live in, by hand. Tapping Create makes
 * `<folder>/lilyai/AGENT.md` and `<folder>/lilyai/<project>/README.md` and `AGENT.md` (nothing that exists is overwritten),
 * remembers the folder, and offers to open the editor on it. Only the folder's path is kept, in the app's private preferences.
 */
@Composable
fun WorkspaceFolderGroup(activity: Activity) {
    val location = remember { WorkspaceLocation(activity) }
    var folder by remember { mutableStateOf(location.folder.ifBlank { WorkspaceLocation.defaultFolder() }) }
    var project by remember { mutableStateOf(WorkspaceSetup.DEFAULT_PROJECT) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var ready by remember { mutableStateOf(false) }
    val granted = WorkspaceLocation.accessGranted(activity)
    val inUse = remember(message, granted) { location.sharedRoot() }

    val status = when {
        inUse != null -> "In use now: ${inUse.path}"
        location.folder.isBlank() -> "In use now: the app's private workspace. No other app can see it."
        else -> "The chosen folder cannot be used yet, so the private workspace is in use. Allow file access, then tap Create."
    }

    SectionTitle(
        "Workspace folder",
        hint = "Where your project files live, so Termux and your file manager see them too. Keys and settings stay private.",
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, style = MaterialTheme.typography.bodyMedium, color = LilyUi.colors.textDim)
        LilyTextField(
            folder,
            { folder = it },
            "Folder",
            placeholder = "/storage/emulated/0/Documents",
            helper = "A folder called lilyai is made inside it",
            mono = true,
            keyboardType = KeyboardType.Uri,
        )
        LilyTextField(
            project,
            { project = it },
            "First project",
            helper = "Becomes a folder with README.md and AGENT.md",
            mono = true,
        )
        if (!granted) {
            Text(
                "Android has not allowed this app to use shared files yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = LilyUi.colors.textDim,
            )
            LilyButton("Allow file access", onClick = { WorkspaceLocation.askAccess(activity) }, kind = ButtonKind.Tonal)
        }
        LilyButton(
            "Create lilyai folder",
            onClick = {
                val path = folder.trim()
                if (path.isEmpty()) {
                    message = "Type a folder first." to Tone.Stamen
                } else {
                    try {
                        val result = WorkspaceSetup.create(File(path), project)
                        location.folder = path
                        ready = true
                        message = "Ready at ${result.root.path}. Made ${result.created.size} file(s); kept ${result.kept.size} that were already there." to Tone.Pad
                    } catch (e: Exception) {
                        ready = false
                        message = "Could not create it: ${e.message}. Allow file access and try again." to Tone.Coral
                    }
                }
            },
        )
        if (ready) {
            LilyButton("Open the editor on it now", onClick = { activity.recreate() }, kind = ButtonKind.Tonal)
        }
        if (location.folder.isNotBlank()) {
            LilyButton(
                "Use the private workspace again",
                onClick = {
                    location.folder = ""
                    activity.recreate()
                },
                kind = ButtonKind.Tonal,
            )
        }
        val current = message
        if (current != null) {
            Banner(current.first, current.second, Modifier, actionLabel = "Dismiss", onAction = { message = null })
        }
    }
    Hairline(Modifier.padding(horizontal = 20.dp))
}
