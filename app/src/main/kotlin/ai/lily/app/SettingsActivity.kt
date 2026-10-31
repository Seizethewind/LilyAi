package ai.lily.app

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig
import ai.lily.app.settings.AppSettings
import ai.lily.app.settings.EncryptedSettingsStore

/** Lets the user enter router, Turso (Journal + Proven facts) and Pandastack settings. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var store: EncryptedSettingsStore
    private lateinit var current: AppSettings

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
        field(R.id.panda_url).setText(current.pandastack?.apiUrl.orEmpty())
        field(R.id.panda_key).setText(current.pandastack?.apiKey.orEmpty())

        findViewById<Button>(R.id.save).setOnClickListener {
            save()
            finish()
        }
    }

    private fun field(id: Int) = findViewById<EditText>(id)
    private fun value(id: Int) = field(id).text.toString().trim()

    private fun save() {
        val journal = value(R.id.journal_url).takeIf { it.isNotEmpty() }
            ?.let { TursoConfig(it, value(R.id.journal_token)) }
        val facts = value(R.id.facts_url).takeIf { it.isNotEmpty() }
            ?.let { TursoConfig(it, value(R.id.facts_token)) }
        val panda = value(R.id.panda_key).takeIf { it.isNotEmpty() }?.let { key ->
            val default = PandaStackConfig(apiKey = key)
            default.copy(apiUrl = value(R.id.panda_url).ifEmpty { default.apiUrl })
        }
        store.save(
            current.copy(
                router = current.router.copy(
                    baseUrl = value(R.id.router_url).ifEmpty { current.router.baseUrl },
                    apiKey = value(R.id.router_key),
                ),
                journalDb = journal,
                factsDb = facts,
                pandastack = panda,
            )
        )
    }
}
