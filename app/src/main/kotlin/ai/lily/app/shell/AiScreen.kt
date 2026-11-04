package ai.lily.app.shell

import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.RouterConfig
import ai.lily.app.gateway.RouterModels
import ai.lily.app.settings.AppSettings
import ai.lily.app.settings.EncryptedSettingsStore
import ai.lily.app.workers.WorkerProfile
import ai.lily.app.workers.WorkerTemplate
import ai.lily.app.workers.WorkerTemplates
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
        status = status + (index to "Loading\u2026")
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

/** The AI section: routers and models at the top, then the workers Lily can use. */
@Composable
fun AiScreen(store: EncryptedSettingsStore, onChanged: () -> Unit, modifier: Modifier = Modifier) {
    val state = remember { AiState(store, onChanged) }
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<WorkerProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LazyColumn(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        item { Text("AI", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 12.dp)) }
        item { Text("Routers", style = MaterialTheme.typography.titleMedium) }
        item { RouterCard(0, state, scope) }
        item { RouterCard(1, state, scope) }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Workers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = true }) { Text("Create several") }
                TextButton(onClick = { editing = WorkerProfile(UUID.randomUUID().toString(), "", WorkerType.GENERAL, 0, "", "") }) { Text("Add") }
            }
        }
        note?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) } }
        if (state.settings.workers.isEmpty()) {
            item { Text("No workers yet, so the built-in ones answer. Add one, or create several from a template.", style = MaterialTheme.typography.bodySmall) }
        }
        items(state.settings.workers, key = { it.id }) { p -> WorkerRow(p, state) { editing = p } }
    }
    editing?.let { WorkerEditor(it, state) { editing = null } }
    if (creating) CreateSeveralDialog(state, onDismiss = { creating = false }) { message ->
        note = message
        creating = false
    }
}

@Composable
private fun RouterCard(index: Int, state: AiState, scope: CoroutineScope) {
    val config = if (index == 0) state.settings.router else state.settings.router2
    var url by remember(config?.baseUrl) { mutableStateOf(config?.baseUrl.orEmpty()) }
    var key by remember(config?.apiKey) { mutableStateOf(config?.apiKey.orEmpty()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Router ${index + 1}", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Address (ends with /v1)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { state.saveRouter(index, url, key) }) { Text("Save") }
            TextButton(onClick = { scope.launch { state.loadModels(index) } }, enabled = config != null) { Text("Load models") }
            Text(state.status[index].orEmpty(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WorkerRow(p: WorkerProfile, state: AiState, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
            Text(p.name.ifBlank { "(no name)" }, style = MaterialTheme.typography.titleSmall)
            Text(
                "${p.role.name.lowercase()} \u00B7 router ${p.router + 1} \u00B7 ${p.model.ifBlank { "default model" }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = p.enabled, onCheckedChange = { on -> state.upsert(p.copy(enabled = on)) })
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun WorkerEditor(start: WorkerProfile, state: AiState, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var role by remember { mutableStateOf(start.role) }
    var router by remember { mutableStateOf(start.router) }
    var model by remember { mutableStateOf(start.model) }
    var instructions by remember { mutableStateOf(start.instructions) }
    val known = state.settings.workers.any { it.id == start.id }
    val offered = state.models[router].orEmpty()
    val matches = offered.filter { model.isBlank() || (it.contains(model, ignoreCase = true) && it != model) }.take(8)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (known) "Edit worker" else "Add worker") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Start from a template", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (t in WorkerTemplates.all) {
                        TextButton(onClick = {
                            role = t.role
                            instructions = t.instructions
                            if (name.isBlank()) name = t.label
                        }) { Text(t.label) }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Role", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                for (t in WorkerType.values()) Choice(t.name.lowercase(), role == t) { role = t }
                Text("Router", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Choice("Router 1", router == 0) { router = 0 }
                Choice("Router 2", router == 1) { router = 1 }
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (offered.isEmpty()) {
                    Text("Load this router's models in the Routers part to pick one from a list.", style = MaterialTheme.typography.bodySmall)
                }
                for (m in matches) TextButton(onClick = { model = m }) { Text(m, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Instructions") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    state.upsert(start.copy(name = name.trim(), role = role, router = router, model = model.trim(), instructions = instructions.trim()))
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (known) {
                    TextButton(onClick = {
                        state.remove(start.id)
                        onDismiss()
                    }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun CreateSeveralDialog(state: AiState, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var template by remember { mutableStateOf(WorkerTemplates.all.first()) }
    var count by remember { mutableStateOf("10") }
    var routers by remember { mutableStateOf(2) }
    var filter by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create several workers") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Each worker gets a different model from the routers' lists. Load the models first.", style = MaterialTheme.typography.bodySmall)
                Text("Template", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                for (t in WorkerTemplates.all) Choice("${t.label} (${t.role.name.lowercase()})", template == t) { template = t }
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it.filter(Char::isDigit).take(2) },
                    label = { Text("How many (1 to 30)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Use models from", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Choice("Router 1", routers == 0) { routers = 0 }
                Choice("Router 2", routers == 1) { routers = 1 }
                Choice("Both routers", routers == 2) { routers = 2 }
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Only models containing (for example free)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(state.createMany(template, (count.toIntOrNull() ?: 1).coerceIn(1, 30), filter, routers)) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
