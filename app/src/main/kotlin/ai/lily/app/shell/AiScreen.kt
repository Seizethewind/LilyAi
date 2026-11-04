package ai.lily.app.shell

import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.RouterConfig
import ai.lily.app.gateway.RouterModels
import ai.lily.app.settings.AppSettings
import ai.lily.app.settings.EncryptedSettingsStore
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.ChoiceChip
import ai.lily.app.ui.ConfirmDialog
import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilySheet
import ai.lily.app.ui.LilySwitch
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ListRow
import ai.lily.app.ui.ScreenFrame
import ai.lily.app.ui.ScreenHeader
import ai.lily.app.ui.SectionTitle
import ai.lily.app.ui.StatusPill
import ai.lily.app.ui.Tone
import ai.lily.app.workers.WorkerProfile
import ai.lily.app.workers.WorkerTemplate
import ai.lily.app.workers.WorkerTemplates
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * What the AI section shows and changes: the two routers, the models each one offers, and the workers. Every change is saved
 * to the encrypted store at once, and [onChanged] tells the app to rebuild Lily so she uses it from her next answer.
 */
class AiState(private val store: EncryptedSettingsStore, private val onChanged: () -> Unit) {
    var settings by mutableStateOf(store.load())
        private set
    var models by mutableStateOf<Map<Int, List<String>>>(emptyMap())
        private set
    var status by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    private fun commit(next: AppSettings) {
        store.save(next)
        settings = next
        onChanged()
    }

    fun saveRouter(index: Int, url: String, key: String) {
        val address = url.trim()
        if (index == 0) {
            commit(settings.copy(router = settings.router.copy(baseUrl = address.ifBlank { settings.router.baseUrl }, apiKey = key.trim())))
        } else {
            // An empty address removes the second router; workers pointing at it then use the first.
            val next = if (address.isBlank()) null else (settings.router2 ?: RouterConfig()).copy(baseUrl = address, apiKey = key.trim())
            commit(settings.copy(router2 = next))
        }
        status = status + (index to "Saved")
    }

    suspend fun loadModels(index: Int) {
        val config = if (index == 0) settings.router else settings.router2
        if (config == null) {
            status = status + (index to "Save an address first")
            return
        }
        status = status + (index to "Loading…")
        RouterModels.list(config)
            .onSuccess {
                models = models + (index to it)
                status = status + (index to "${it.size} models")
            }
            .onFailure { status = status + (index to "Could not load: ${it.message}") }
    }

    fun upsert(p: WorkerProfile) {
        val exists = settings.workers.any { it.id == p.id }
        commit(settings.copy(workers = if (exists) settings.workers.map { if (it.id == p.id) p else it } else settings.workers + p))
    }

    fun remove(id: String) = commit(settings.copy(workers = settings.workers.filter { it.id != id }))

    /**
     * Makes [count] workers from a template, giving each a different model from the loaded lists (reusing them in turn when
     * there are fewer models than workers). [routers] is 0 or 1 for one router, 2 for both. Only models whose id contains [filter] are used.
     */
    fun createMany(template: WorkerTemplate, count: Int, filter: String, routers: Int): String {
        val f = filter.trim().lowercase()
        fun pool(i: Int) = models[i].orEmpty().filter { f.isEmpty() || it.lowercase().contains(f) }.map { i to it }
        val choices = when (routers) {
            0 -> pool(0)
            1 -> pool(1)
            else -> {
                val a = pool(0)
                val b = pool(1)
                (0 until maxOf(a.size, b.size)).flatMap { listOfNotNull(a.getOrNull(it), b.getOrNull(it)) }
            }
        }
        if (choices.isEmpty()) return "No models match. Load the router's models first, or loosen the filter."
        val already = settings.workers.count { it.role == template.role }
        val made = (0 until count).map { i ->
            val (router, model) = choices[i % choices.size]
            WorkerProfile(UUID.randomUUID().toString(), "${template.label} ${already + i + 1}", template.role, router, model, template.instructions)
        }
        commit(settings.copy(workers = settings.workers + made))
        return "Created ${made.size} ${template.label.lowercase()} workers on ${minOf(count, choices.size)} different models."
    }
}

/** TEAM is Lily's own role and is never created by hand, so it is not offered anywhere in this screen. */
private val CreatableRoles = WorkerType.values().filter { it != WorkerType.TEAM }

private fun roleLabel(role: WorkerType): String = role.name.lowercase().replaceFirstChar { it.uppercase() }

/** The AI section: routers and models at the top, then the workers Lily can use. */
@Composable
fun AiScreen(store: EncryptedSettingsStore, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val state = remember { AiState(store, onChanged) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<WorkerProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    ScreenFrame(modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { ScreenHeader("AI", subtitle = "Routers choose the models. Workers do the jobs.") }
            item { SectionTitle("Routers", hint = "The self-hosted endpoints Lily sends her questions to.") }
            item { RouterBlock(0, state, scope) }
            item { Hairline(Modifier.padding(horizontal = 20.dp)) }
            item { RouterBlock(1, state, scope) }
            item {
                SectionTitle("Workers", hint = "Each one has its own role, model and instructions.") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LilyButton("Create several", onClick = { creating = true }, kind = ButtonKind.Quiet)
                        LilyButton("Add", onClick = { editing = WorkerProfile(UUID.randomUUID().toString(), "", WorkerType.GENERAL, 0, "", "") }, kind = ButtonKind.Tonal)
                    }
                }
            }
            note?.let { message ->
                item {
                    Banner(message, Tone.Pad, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), actionLabel = "Dismiss", onAction = { note = null })
                }
            }
            if (state.settings.workers.isEmpty()) {
                item {
                    EmptyState(
                        title = "No workers yet",
                        message = "Lily's built-in workers answer until you add your own. Start from a template, or make several at once.",
                    )
                }
            }
            items(state.settings.workers, key = { it.id }) { p ->
                Column {
                    ListRow(
                        title = p.name.ifBlank { "(no name)" },
                        subtitle = "${roleLabel(p.role)} · Router ${p.router + 1} · ${p.model.ifBlank { "default model" }}",
                        onClick = { editing = p },
                        trailing = { LilySwitch(p.enabled, { on -> state.upsert(p.copy(enabled = on)) }) },
                    )
                    Hairline(Modifier.padding(horizontal = 20.dp))
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
    editing?.let { WorkerSheet(it, state) { editing = null } }
    if (creating) CreateSeveralSheet(state, onDismiss = { creating = false }) { message ->
        note = message
        creating = false
    }
}

@Composable
private fun RouterBlock(index: Int, state: AiState, scope: CoroutineScope) {
    val config = if (index == 0) state.settings.router else state.settings.router2
    var url by remember(config?.baseUrl) { mutableStateOf(config?.baseUrl.orEmpty()) }
    var key by remember(config?.apiKey) { mutableStateOf(config?.apiKey.orEmpty()) }
    val status = state.status[index]
    val tone = when {
        status == null -> Tone.Neutral
        status.startsWith("Could not") -> Tone.Coral
        status.startsWith("Save an") -> Tone.Stamen
        status.startsWith("Loading") -> Tone.Petal
        else -> Tone.Pad
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (index == 0) "Router 1" else "Router 2 (optional)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (status != null) StatusPill(status, tone)
        }
        LilyTextField(
            value = url,
            onValueChange = { url = it },
            label = "Address",
            placeholder = "https://your-router.example/v1",
            helper = if (index == 0) "Ends with /v1" else "Leave empty to use only one router",
            mono = true,
            keyboardType = KeyboardType.Uri,
        )
        LilyTextField(value = key, onValueChange = { key = it }, label = "API key", secret = true, mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilyButton("Save", onClick = { state.saveRouter(index, url, key) })
            LilyButton("Load models", onClick = { scope.launch { state.loadModels(index) } }, kind = ButtonKind.Tonal, enabled = config != null)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LilyUi.colors.textDim)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkerSheet(start: WorkerProfile, state: AiState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var role by remember { mutableStateOf(start.role) }
    var router by remember { mutableStateOf(start.router) }
    var model by remember { mutableStateOf(start.model) }
    var instructions by remember { mutableStateOf(start.instructions) }
    var confirmDelete by remember { mutableStateOf(false) }
    val known = state.settings.workers.any { it.id == start.id }
    val offered = state.models[router].orEmpty()
    val matches = offered.filter { model.isBlank() || (it.contains(model, ignoreCase = true) && it != model) }.take(6)

    LilySheet(if (known) "Edit worker" else "Add worker", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Start from a template")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in WorkerTemplates.all) {
                        ChoiceChip(t.label, selected = false, onClick = {
                            role = t.role
                            instructions = t.instructions
                            if (name.isBlank()) name = t.label
                        })
                    }
                }
            }
            LilyTextField(name, { name = it }, "Name", placeholder = "For example Tester 1")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Role")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in CreatableRoles) ChoiceChip(roleLabel(t), role == t, onClick = { role = t })
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Router")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Router 1", router == 0, onClick = { router = 0 })
                    ChoiceChip("Router 2", router == 1, onClick = { router = 1 })
                }
            }
            LilyTextField(
                value = model,
                onValueChange = { model = it },
                label = "Model",
                placeholder = "Empty uses the router's default",
                helper = if (offered.isEmpty()) "Load this router's models in the Routers part to pick one from a list." else null,
                mono = true,
            )
            if (matches.isNotEmpty()) {
                Column {
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
            LilyTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = "Instructions",
                helper = "Short rules for this worker's job.",
                singleLine = false,
                minLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LilyButton(
                    "Save worker",
                    onClick = {
                        state.upsert(start.copy(name = name.trim(), role = role, router = router, model = model.trim(), instructions = instructions.trim()))
                        onDismiss()
                    },
                    enabled = name.isNotBlank(),
                )
                LilyButton("Cancel", onClick = onDismiss, kind = ButtonKind.Quiet)
                if (known) LilyButton("Delete", onClick = { confirmDelete = true }, kind = ButtonKind.Danger)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${start.name.ifBlank { "this worker" }}?",
            message = "Its role goes back to the built-in worker if no other one covers it.",
            confirmLabel = "Delete",
            danger = true,
            onConfirm = {
                confirmDelete = false
                state.remove(start.id)
                onDismiss()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateSeveralSheet(state: AiState, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var template by remember { mutableStateOf(WorkerTemplates.all.first()) }
    var count by remember { mutableStateOf("10") }
    var routers by remember { mutableStateOf(2) }
    var filter by remember { mutableStateOf("") }
    val noModels = state.models.values.all { it.isEmpty() }
    LilySheet("Create several workers", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (noModels) {
                Banner("Each worker gets a different model, so load the routers' models first.", Tone.Stamen)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Template")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (t in WorkerTemplates.all) ChoiceChip(t.label, template == t, onClick = { template = t })
                }
            }
            LilyTextField(
                value = count,
                onValueChange = { count = it.filter(Char::isDigit).take(2) },
                label = "How many",
                helper = "1 to 30",
                keyboardType = KeyboardType.Number,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Use models from")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Router 1", routers == 0, onClick = { routers = 0 })
                    ChoiceChip("Router 2", routers == 1, onClick = { routers = 1 })
                    ChoiceChip("Both", routers == 2, onClick = { routers = 2 })
                }
            }
            LilyTextField(
                value = filter,
                onValueChange = { filter = it },
                label = "Only models containing",
                placeholder = "For example free",
                helper = "Optional",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LilyButton(
                    "Create",
                    onClick = { onDone(state.createMany(template, (count.toIntOrNull() ?: 1).coerceIn(1, 30), filter, routers)) },
                )
                LilyButton("Cancel", onClick = onDismiss, kind = ButtonKind.Quiet)
            }
        }
    }
}
