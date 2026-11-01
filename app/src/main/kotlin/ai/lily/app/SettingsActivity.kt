package ai.lily.app

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import ai.lily.app.memory.CapabilityProbe
import ai.lily.app.memory.KnowledgeSchema
import ai.lily.app.memory.TursoClient
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig
import ai.lily.app.settings.AppSettings
import ai.lily.app.settings.EncryptedSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Lets the user enter router, Turso (Journal, Proven facts and Knowledge) and Pandastack settings. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var store: EncryptedSettingsStore
    private lateinit var current: AppSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = EncryptedSettingsStore(this)
        current = store.load()

        field(R.id.router_url).setText(current.router.baseUrl)
        field(R.id.router_key).setText(current.router.apiKey)
        field(R.id.journal_url).setText(current.journalDb?.url.orEmpty())
        field(R.id.journal_token).setText(current.journalDb?.authToken.orEmpty())
        field(R.id.facts_url).setText(current.factsDb?.url.orEmpty())
        field(R.id.facts_token).setText(current.factsDb?.authToken.orEmpty())
        field(R.id.knowledge_url).setText(current.knowledgeDb?.url.orEmpty())
        field(R.id.knowledge_token).setText(current.knowledgeDb?.authToken.orEmpty())
        field(R.id.panda_url).setText(current.pandastack?.apiUrl.orEmpty())
        field(R.id.panda_key).setText(current.pandastack?.apiKey.orEmpty())

        findViewById<Button>(R.id.check_knowledge).setOnClickListener { checkKnowledge() }
        findViewById<Button>(R.id.save).setOnClickListener {
            val problem = KnowledgeSchema.configProblem(build())
            if (problem != null) {
                show("Not saved", problem)
            } else {
                store.save(build())
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun field(id: Int) = findViewById<EditText>(id)
    private fun value(id: Int) = field(id).text.toString().trim()

    private fun show(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
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
        return current.copy(
            router = current.router.copy(
                baseUrl = value(R.id.router_url).ifEmpty { current.router.baseUrl },
                apiKey = value(R.id.router_key),
            ),
            journalDb = journal,
            factsDb = facts,
            knowledgeDb = knowledge,
            pandastack = panda,
        )
    }

    /** Asks the knowledge database what it supports (transactions, vector search, full-text search). Changes nothing in it. */
    private fun checkKnowledge() {
        val settings = build()
        val config = settings.knowledgeDb
        if (config == null) {
            show("Knowledge database", "Enter the knowledge database URL and token first.")
            return
        }
        KnowledgeSchema.configProblem(settings)?.let {
            show("Knowledge database", it)
            return
        }
        show("Knowledge database", "Checking\u2026")
        scope.launch {
            val text = try {
                CapabilityProbe(TursoClient(config)).run().summary()
            } catch (e: Exception) {
                "Could not reach the database: ${e.message}"
            }
            show("Knowledge database", text)
        }
    }
}
