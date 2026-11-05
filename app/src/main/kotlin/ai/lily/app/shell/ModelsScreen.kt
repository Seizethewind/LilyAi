package ai.lily.app.shell

import ai.lily.app.gateway.ModelFilter
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.ChoiceChip
import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilyIconButton
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ListRow
import ai.lily.app.ui.ScreenFrame
import ai.lily.app.ui.ScreenHeader
import ai.lily.app.ui.StatusPill
import ai.lily.app.ui.Tone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Model list page: every model both routers offer, filtered by router chip and by words, with a Refresh button. Tapping a
 * row copies the model id. The page loads the lists itself the first time it opens with nothing loaded; [AiState.models] is only
 * read and refreshed here, so the Workers page keeps suggesting from the same lists. [onRouters] jumps to the Router config page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(state: AiState, onMenu: (() -> Unit)?, onRouters: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    // Index in this list is the router index the settings and AiState use: 0 is always there, 1 only when a second router is saved.
    val routers = listOfNotNull(state.settings.router, state.settings.router2)
    var source by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf<String?>(null) }
    // A chosen router that was removed on the Router config page falls back to All.
    val shown = source?.takeIf { it < routers.size }
    val rows = remember(state.models, shown, query) { ModelFilter.rows(state.models, shown, query) }
    val total = remember(state.models, shown) { ModelFilter.count(state.models, shown) }
    val statuses = routers.indices.mapNotNull { i -> state.status[i]?.let { i to it } }
    val loading = statuses.any { it.second.startsWith("Loading") }

    fun refresh() {
        for (i in routers.indices) scope.launch { state.loadModels(i) }
    }
    LaunchedEffect(Unit) { if (state.models.isEmpty()) refresh() }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(2000)
            copied = null
        }
    }
    val menu: @Composable RowScope.() -> Unit = { if (onMenu != null) LilyIconButton(Icons.Filled.Menu, "Open AI menu", onMenu) }

    ScreenFrame(modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { ScreenHeader(AiPage.Models.label, subtitle = "Every model your routers offer. Tap one to copy its id.", actions = menu) }
            item {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("All", shown == null, onClick = { source = null })
                    for (i in routers.indices) ChoiceChip(routers[i].title, shown == i, onClick = { source = i })
                }
            }
            item {
                LilyTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Find a model",
                    placeholder = "For example gpt free",
                    helper = "Every word must be in the model id.",
                    mono = true,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${rows.size} of $total", style = MaterialTheme.typography.labelLarge, color = LilyUi.colors.textDim, modifier = Modifier.weight(1f))
                    LilyButton("Refresh", onClick = { refresh() }, kind = ButtonKind.Tonal, enabled = !loading)
                }
            }
            if (statuses.isNotEmpty()) {
                item {
                    FlowRow(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((i, text) in statuses) {
                            if (!text.startsWith("Could not")) StatusPill("${routers[i].title}: $text", statusTone(text))
                        }
                    }
                }
            }
            // A failed load keeps the models already shown, so the reason sits above the list instead of replacing it.
            for ((i, text) in statuses) {
                if (text.startsWith("Could not")) {
                    item(key = "failed-$i") {
                        Banner(
                            "${routers[i].title}: $text",
                            Tone.Coral,
                            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            actionLabel = "Router config",
                            onAction = onRouters,
                        )
                    }
                }
            }
            copied?.let { id ->
                item(key = "copied") { Banner("Copied $id", Tone.Pad, Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
            }
            if (state.models.values.all { it.isEmpty() }) {
                if (!loading) {
                    item(key = "empty") {
                        EmptyState(
                            title = "No models loaded",
                            message = "Save a router address on the Router config page, then press Refresh to list its models.",
                            actionLabel = "Open Router config",
                            onAction = onRouters,
                        )
                    }
                }
            } else if (rows.isEmpty()) {
                item(key = "none") {
                    Text(
                        "No model matches.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LilyUi.colors.textDim,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(rows, key = { "${it.router}-${it.id}" }) { row ->
                androidx.compose.foundation.layout.Column {
                    ListRow(
                        title = row.id,
                        subtitle = routers.getOrNull(row.router)?.title ?: "Router ${row.router + 1}",
                        onClick = {
                            clipboard.setText(AnnotatedString(row.id))
                            copied = row.id
                        },
                    )
                    Hairline(Modifier.padding(horizontal = 20.dp))
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

private fun statusTone(text: String): Tone = when {
    text.startsWith("Save an") -> Tone.Stamen
    text.startsWith("Loading") -> Tone.Petal
    else -> Tone.Pad
}
