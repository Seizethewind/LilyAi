package ai.lily.app

import ai.lily.app.gateway.EmbeddingEndpoint
import ai.lily.app.memory.CapabilityProbe
import ai.lily.app.memory.KnowledgeSchema
import ai.lily.app.memory.TursoClient
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig
import ai.lily.app.settings.AppSettings
import ai.lily.app.settings.EncryptedSettingsStore
import android.app.Activity
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The settings form: router, Turso (Journal, Proven facts and Knowledge), embeddings, Pandastack and check commands.
 * Secrets (keys and tokens) sit in password fields and are saved only in the encrypted store.
 * [root] is the inflated activity_settings layout; [onSaved] runs after a successful save.
 */
class SettingsPanel(
    private val activity: Activity,
    private val root: View,
    private val scope: CoroutineScope,
    private val onSaved: () -> Unit,
) {
    private val store = EncryptedSettingsStore(activity)
    private var current: AppSettings = store.load()

    fun bind() {
        field(R.id.router_url).setText(current.router.baseUrl)
        field(R.id.router_key).setText(current.router.apiKey)
        field(R.id.journal_url).setText(current.journalDb?.url.orEmpty())
        field(R.id.journal_token).setText(current.journalDb?.authToken.orEmpty())
        field(R.id.facts_url).setText(current.factsDb?.url.orEmpty())
        field(R.id.facts_token).setText(current.factsDb?.authToken.orEmpty())
        field(R.id.knowledge_url).setText(current.knowledgeDb?.url.orEmpty())
        field(R.id.knowledge_token).setText(current.knowledgeDb?.authToken.orEmpty())
        field(R.id.embed_url).setText(current.embedding?.baseUrl.orEmpty())
        field(R.id.embed_key).setText(current.embedding?.apiKey.orEmpty())
        field(R.id.embed_model).setText(current.embedding?.model.orEmpty())
        field(R.id.embed_tag).setText(current.embedding?.tag.orEmpty())
        field(R.id.panda_url).setText(current.pandastack?.apiUrl.orEmpty())
        field(R.id.panda_key).setText(current.pandastack?.apiKey.orEmpty())
        field(R.id.verify_commands).setText(current.verifyCommands.joinToString("\n"))

        root.findViewById<Button>(R.id.check_knowledge).setOnClickListener { checkKnowledge() }
        root.findViewById<Button>(R.id.save).setOnClickListener {
            val settings = buildOrExplain() ?: return@setOnClickListener
            val problem = KnowledgeSchema.configProblem(settings)
            if (problem != null) {
                show("Not saved", problem)
            } else {
                store.save(settings)
                current = settings
                onSaved()
                show("Saved", "Your settings are saved. Lily uses them from her next answer.")
            }
        }
    }

    private fun field(id: Int) = root.findViewById<EditText>(id)
    private fun value(id: Int) = field(id).text.toString().trim()

    private fun show(title: String, message: String): AlertDialog =
        AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()

    /** The settings as typed, or null after telling the person what is wrong (for example an embedding model left as "auto"). */
    private fun buildOrExplain(): AppSettings? = try {
        build()
    } catch (e: IllegalArgumentException) {
        show("Not saved", e.message ?: "A setting is not valid.")
        null
    }

    private fun build(): AppSettings {
        val journal = value(R.id.journal_url).takeIf { it.isNotEmpty() }
            ?.let { TursoConfig(it, value(R.id.journal_token)) }
        val facts = value(R.id.facts_url).takeIf { it.isNotEmpty() }
            ?.let { TursoConfig(it, value(R.id.facts_token)) }
        val knowledge = value(R.id.knowledge_url).takeIf { it.isNotEmpty() }
            ?.let { TursoConfig(it, value(R.id.knowledge_token)) }
        val panda = value(R.id.panda_key).takeIf { it.isNotEmpty() }?.let { key ->
            val default = PandaStackConfig(apiKey = key)
            default.copy(apiUrl = value(R.id.panda_url).ifEmpty { default.apiUrl })
        }
        // EmbeddingEndpoint checks itself: no model, or "auto", is refused with a message the person can act on.
        val embedding = value(R.id.embed_url).takeIf { it.isNotEmpty() }?.let { url ->
            val model = value(R.id.embed_model)
            EmbeddingEndpoint(
                name = "embedding",
                baseUrl = url,
                apiKey = value(R.id.embed_key),
                model = model,
                tag = value(R.id.embed_tag).ifEmpty { EmbeddingEndpoint.defaultTag(model, null) },
            )
        }
        val commands = field(R.id.verify_commands).text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        return current.copy(
            router = current.router.copy(
                baseUrl = value(R.id.router_url).ifEmpty { current.router.baseUrl },
                apiKey = value(R.id.router_key),
            ),
            journalDb = journal,
            factsDb = facts,
            knowledgeDb = knowledge,
            pandastack = panda,
            embedding = embedding,
            verifyCommands = commands,
        )
    }

    /** Asks the knowledge database what it supports (transactions, vector search, full-text search). Changes nothing in it. */
    private fun checkKnowledge() {
        val settings = buildOrExplain() ?: return
        val config = settings.knowledgeDb
        if (config == null) {
            show("Knowledge database", "Enter the knowledge database URL and token first.")
            return
        }
        KnowledgeSchema.configProblem(settings)?.let {
            show("Knowledge database", it)
            return
        }
        val waiting = show("Knowledge database", "Checking\u2026")
        scope.launch {
            val text = try {
                CapabilityProbe(TursoClient(config)).run().summary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not reach the database: ${e.message}"
            }
            waiting.dismiss()
            show("Knowledge database", text)
        }
    }
}

/** The Settings section: the settings form as a full screen, coloured to match the rest of the app. */
@androidx.compose.runtime.Composable
fun SettingsScreen(activity: Activity, scope: CoroutineScope, onSaved: () -> Unit, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background.toArgb()
    val text = MaterialTheme.colorScheme.onBackground.toArgb()
    AndroidView(
        modifier = modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        factory = { context ->
            LayoutInflater.from(context).inflate(R.layout.activity_settings, null).also {
                SettingsPanel(activity, it, scope, onSaved).bind()
            }
        },
        update = { view ->
            view.setBackgroundColor(background)
            recolor(view, text)
        },
    )
}

/** The form is a classic layout, so its labels follow the app's colours here. Buttons keep their own look. */
private fun recolor(view: View, text: Int) {
    if (view is Button) return
    if (view is TextView) {
        view.setTextColor(text)
        view.setHintTextColor((text and 0x00FFFFFF) or 0x80000000.toInt())
    }
    if (view is ViewGroup) for (i in 0 until view.childCount) recolor(view.getChildAt(i), text)
}
