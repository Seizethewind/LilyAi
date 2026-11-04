package ai.lily.app

import ai.lily.app.gateway.EmbeddingEndpoint
import ai.lily.app.memory.CapabilityProbe
import ai.lily.app.memory.KnowledgeSchema
import ai.lily.app.memory.TursoClient
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig
import ai.lily.app.settings.AppSettings
import ai.lily.app.settings.EncryptedSettingsStore
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyBloom
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ScreenFrame
import ai.lily.app.ui.ScreenHeader
import ai.lily.app.ui.SectionTitle
import ai.lily.app.ui.Tone
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What is typed in the settings form. Routers, models and workers are not here: they live in the AI section and are kept as they are. */
private class SettingsForm(initial: AppSettings) {
    var journalUrl by mutableStateOf(initial.journalDb?.url.orEmpty())
    var journalToken by mutableStateOf(initial.journalDb?.authToken.orEmpty())
    var factsUrl by mutableStateOf(initial.factsDb?.url.orEmpty())
    var factsToken by mutableStateOf(initial.factsDb?.authToken.orEmpty())
    var knowledgeUrl by mutableStateOf(initial.knowledgeDb?.url.orEmpty())
    var knowledgeToken by mutableStateOf(initial.knowledgeDb?.authToken.orEmpty())
    var embedUrl by mutableStateOf(initial.embedding?.baseUrl.orEmpty())
    var embedKey by mutableStateOf(initial.embedding?.apiKey.orEmpty())
    var embedModel by mutableStateOf(initial.embedding?.model.orEmpty())
    var embedTag by mutableStateOf(initial.embedding?.tag.orEmpty())
    var pandaUrl by mutableStateOf(initial.pandastack?.apiUrl.orEmpty())
    var pandaKey by mutableStateOf(initial.pandastack?.apiKey.orEmpty())
    var verifyCommands by mutableStateOf(initial.verifyCommands.joinToString("\n"))

    /** The settings as typed, on top of [current] so routers and workers edited elsewhere are never overwritten. Throws IllegalArgumentException with a message the person can act on. */
    fun build(current: AppSettings): AppSettings {
        val journal = journalUrl.trim().takeIf { it.isNotEmpty() }?.let { TursoConfig(it, journalToken.trim()) }
        val facts = factsUrl.trim().takeIf { it.isNotEmpty() }?.let { TursoConfig(it, factsToken.trim()) }
        val knowledge = knowledgeUrl.trim().takeIf { it.isNotEmpty() }?.let { TursoConfig(it, knowledgeToken.trim()) }
        val panda = pandaKey.trim().takeIf { it.isNotEmpty() }?.let { key ->
            val default = PandaStackConfig(apiKey = key)
            default.copy(apiUrl = pandaUrl.trim().ifEmpty { default.apiUrl })
        }
        // EmbeddingEndpoint checks itself: no model, or "auto", is refused with a message the person can act on.
        val embedding = embedUrl.trim().takeIf { it.isNotEmpty() }?.let { url ->
            val model = embedModel.trim()
            EmbeddingEndpoint(
                name = "embedding",
                baseUrl = url,
                apiKey = embedKey.trim(),
                model = model,
                tag = embedTag.trim().ifEmpty { EmbeddingEndpoint.defaultTag(model, null) },
            )
        }
        val commands = verifyCommands.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return current.copy(
            journalDb = journal,
            factsDb = facts,
            knowledgeDb = knowledge,
            pandastack = panda,
            embedding = embedding,
            verifyCommands = commands,
        )
    }
}

/** A named group of fields with a line saying what it is for. */
@Composable
private fun Group(title: String, hint: String, content: @Composable () -> Unit) {
    SectionTitle(title, hint = hint)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
    }
    Hairline(Modifier.padding(horizontal = 20.dp))
}

/**
 * The Settings section: where Lily's memory, search and sandbox are connected. Keys and tokens are saved only in encrypted
 * app storage. Save sits in a bar at the foot, so it is reachable from any field, and what happened shows right above it.
 */
@Composable
fun SettingsScreen(activity: Activity, scope: CoroutineScope, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val store = remember { EncryptedSettingsStore(activity) }
    val form = remember { SettingsForm(store.load()) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun save() {
        val settings = try {
            form.build(store.load())
        } catch (e: IllegalArgumentException) {
            message = (e.message ?: "A setting is not valid.") to Tone.Coral
            return
        }
        val problem = KnowledgeSchema.configProblem(settings)
        if (problem != null) {
            message = "Not saved. $problem" to Tone.Coral
            return
        }
        store.save(settings)
        onSaved()
        message = "Saved. Lily uses these settings from her next answer." to Tone.Pad
    }

    fun checkKnowledge() {
        val settings = try {
            form.build(store.load())
        } catch (e: IllegalArgumentException) {
            message = (e.message ?: "A setting is not valid.") to Tone.Coral
            return
        }
        val config = settings.knowledgeDb
        if (config == null) {
            message = "Enter the knowledge database address and token first." to Tone.Stamen
            return
        }
        val problem = KnowledgeSchema.configProblem(settings)
        if (problem != null) {
            message = problem to Tone.Coral
            return
        }
        checking = true
        message = null
        scope.launch {
            val result = try {
                CapabilityProbe(TursoClient(config)).run().summary() to Tone.Petal
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not reach the database: ${e.message}" to Tone.Coral
            }
            checking = false
            message = result
        }
    }

    ScreenFrame(modifier) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            ScreenHeader(
                "Settings",
                subtitle = "Keys and tokens stay encrypted on this phone. Routers, models and workers are in the AI section.",
            )
            Group("Journal", "The raw log of requests, decisions and results.") {
                LilyTextField(form.journalUrl, { form.journalUrl = it }, "Database address", placeholder = "libsql://…", mono = true, keyboardType = KeyboardType.Uri)
                LilyTextField(form.journalToken, { form.journalToken = it }, "Token", secret = true, mono = true)
            }
            Group("Proven facts", "Only statements that passed a check, each with its evidence.") {
                LilyTextField(form.factsUrl, { form.factsUrl = it }, "Database address", placeholder = "libsql://…", mono = true, keyboardType = KeyboardType.Uri)
                LilyTextField(form.factsToken, { form.factsToken = it }, "Token", secret = true, mono = true)
            }
            Group("Knowledge", "A third, separate database for the library Lily searches.") {
                LilyTextField(form.knowledgeUrl, { form.knowledgeUrl = it }, "Database address", placeholder = "libsql://…", mono = true, keyboardType = KeyboardType.Uri)
                LilyTextField(form.knowledgeToken, { form.knowledgeToken = it }, "Token", secret = true, mono = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LilyButton("Check database", onClick = { checkKnowledge() }, kind = ButtonKind.Tonal, enabled = !checking)
                    if (checking) {
                        LilyBloom(size = 24.dp)
                        Text("Checking", style = MaterialTheme.typography.bodyMedium, color = LilyUi.colors.textDim)
                    }
                }
            }
            Group("Embeddings", "Turns library text into meaning vectors. Leave empty for keyword search only.") {
                LilyTextField(form.embedUrl, { form.embedUrl = it }, "Address", placeholder = "https://…/v1", helper = "Ends with /v1", mono = true, keyboardType = KeyboardType.Uri)
                LilyTextField(form.embedKey, { form.embedKey = it }, "Key", secret = true, mono = true, helper = "Optional")
                LilyTextField(form.embedModel, { form.embedModel = it }, "Model id", mono = true, helper = "A real model id, never auto")
                LilyTextField(form.embedTag, { form.embedTag = it }, "Tag", mono = true, helper = "Optional. Defaults to the model id")
            }
            Group("Sandbox", "Pandastack test-runs code before a fact is promoted.") {
                LilyTextField(form.pandaUrl, { form.pandaUrl = it }, "API address", placeholder = "api.pandastack.ai", helper = "Empty uses the default", mono = true, keyboardType = KeyboardType.Uri)
                LilyTextField(form.pandaKey, { form.pandaKey = it }, "API key", secret = true, mono = true)
                LilyTextField(
                    form.verifyCommands,
                    { form.verifyCommands = it },
                    "Check commands",
                    helper = "One per line. Empty lets Lily detect them.",
                    mono = true,
                    singleLine = false,
                    minLines = 3,
                )
            }
            Text(
                "Lily · version 0.1.0",
                style = MaterialTheme.typography.bodySmall,
                color = LilyUi.colors.textFaint,
                modifier = Modifier.padding(20.dp),
            )
        }
        Column(Modifier.fillMaxWidth().background(LilyUi.colors.raised)) {
            Hairline()
            val current = message
            if (current != null) {
                Banner(
                    current.first,
                    current.second,
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    actionLabel = "Dismiss",
                    onAction = { message = null },
                )
            }
            LilyButton("Save settings", onClick = { save() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}
