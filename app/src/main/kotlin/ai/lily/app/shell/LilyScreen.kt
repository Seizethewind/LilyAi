package ai.lily.app.shell

import ai.lily.app.settings.LilyProfile
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.ChoiceChip
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilyIconButton
import ai.lily.app.ui.LilyMark
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ScreenFrame
import ai.lily.app.ui.ScreenHeader
import ai.lily.app.ui.Tone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lily's picture in a circle: her photo when she has one, otherwise the Lily mark. Decorative, so it has no description. */
@Composable
fun LilyAvatar(photoPath: String, size: Dp, modifier: Modifier = Modifier) {
    val image = remember(photoPath) { LilyPhoto.load(photoPath) }
    if (image != null) {
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(CircleShape))
    } else {
        LilyMark(modifier, size)
    }
}

/**
 * The Lily page: her photo, name, instructions, router and model. Nothing is saved until Save is pressed, then the app rebuilds
 * her so the change applies from her next answer. The router and model are the ones her planning uses; her workers keep their
 * own. The instructions are added to her planning and to every worker's answer.
 */
@Composable
fun LilyScreen(state: AiState, onMenu: (() -> Unit)?, modifier: Modifier = Modifier) {
    val saved = state.settings.lily
    val routers = listOfNotNull(state.settings.router, state.settings.router2)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The drafts start again from what is saved after each save.
    var name by remember(saved) { mutableStateOf(saved.name) }
    var instructions by remember(saved) { mutableStateOf(saved.instructions) }
    var router by remember(saved) { mutableStateOf(saved.router) }
    var model by remember(saved) { mutableStateOf(saved.model) }
    var photo by remember(saved) { mutableStateOf(saved.photoPath) }
    var note by remember { mutableStateOf<String?>(null) }
    // A second router that was removed on the Router config page falls back to the first, the same way LilyFactory does.
    val chosen = if (router < routers.size) router else 0
    val draft = LilyProfile(
        name = name.trim().ifBlank { LilyProfile.DEFAULT_NAME },
        instructions = instructions.trim(),
        router = chosen,
        model = model.trim(),
        photoPath = photo,
    )
    val offered = state.models[chosen].orEmpty()
    val matches = offered.filter { model.isBlank() || (it.contains(model, ignoreCase = true) && it != model) }.take(6)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    LilyPhoto.import(context, uri)?.also { LilyPhoto.cleanup(context, setOf(saved.photoPath, it)) }
                }
                if (path == null) note = "That picture could not be read. Try another one." else photo = path
            }
        }
    }
    val menu: @Composable RowScope.() -> Unit = { if (onMenu != null) LilyIconButton(Icons.Filled.Menu, "Open AI menu", onMenu) }

    ScreenFrame(modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { ScreenHeader(saved.name, subtitle = "Her photo, name and standing instructions.", actions = menu) }
            item {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LilyAvatar(photo, 96.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LilyButton(
                            "Choose photo",
                            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            kind = ButtonKind.Tonal,
                        )
                        if (photo.isNotBlank()) LilyButton("Remove photo", onClick = { photo = "" }, kind = ButtonKind.Quiet)
                    }
                }
            }
            note?.let { message ->
                item(key = "note") {
                    Banner(message, Tone.Pad, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), actionLabel = "Dismiss", onAction = { note = null })
                }
            }
            item {
                LilyTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    placeholder = LilyProfile.DEFAULT_NAME,
                    helper = "Empty goes back to ${LilyProfile.DEFAULT_NAME}.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Router for her planning", style = MaterialTheme.typography.labelMedium, color = LilyUi.colors.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (i in routers.indices) ChoiceChip(routers[i].title, chosen == i, onClick = { router = i })
                    }
                }
            }
            item {
                LilyTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = "Model for her planning",
                    placeholder = "Empty keeps her usual choice",
                    helper = if (offered.isEmpty()) {
                        "Load this router's models on the Router config page to pick one from a list. Workers keep their own models."
                    } else {
                        "Workers keep their own models."
                    },
                    mono = true,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (matches.isNotEmpty()) {
                item(key = "model-matches") {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        for (m in matches) {
                            Text(
                                m,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { model = m }.padding(vertical = 12.dp, horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                LilyTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = "Instructions",
                    helper = "Added to her planning and to every worker's answer, after the worker's own rules.",
                    singleLine = false,
                    minLines = 5,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LilyButton(
                        "Save",
                        onClick = {
                            state.saveLily(draft)
                            LilyPhoto.cleanup(context, setOf(draft.photoPath))
                            note = "Saved. ${draft.name} uses this from her next answer."
                        },
                        enabled = draft != saved,
                    )
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}
