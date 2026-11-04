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
import android.view.View
import android.widget.Button
import android.widget.EditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The classic settings form for [SettingsActivity]: Turso (Journal, Proven facts and Knowledge), embeddings, Pandastack and check commands.
 * Inside the app, the Settings section shows the same fields as a Compose screen (SettingsScreenUi.kt).
 * Routers, models and workers are in the AI section; saving here keeps them exactly as they are.
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

    fun bind() {
        val current = store.load()
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
        // Read the store again so routers and workers edited in the AI section are never overwritten.
        return store.load().copy(
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
        val waiting = show("Knowledge database", "Checking…")
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
