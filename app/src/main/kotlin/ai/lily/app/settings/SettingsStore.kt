package ai.lily.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.lily.app.gateway.RouterConfig
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig

interface SettingsStore {
    fun load(): AppSettings
    fun save(settings: AppSettings)
}

/** Keys and tokens live only in encrypted app storage, never in Git. */
class EncryptedSettingsStore(context: Context) : SettingsStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "lily_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun get(key: String) = prefs.getString(key, "").orEmpty()

    override fun load(): AppSettings {
        val d = RouterConfig()
        val pandaDefault = PandaStackConfig(apiKey = "")
        return AppSettings(
            router = RouterConfig(
                baseUrl = get("router_url").ifBlank { d.baseUrl },
                apiKey = get("router_key"),
                defaultModel = get("router_model").ifBlank { d.defaultModel },
                webSearchModel = get("search_model").ifBlank { d.webSearchModel },
            ),
            journalDb = get("journal_url").takeIf { it.isNotBlank() }
                ?.let { TursoConfig(it, get("journal_token")) },
            factsDb = get("facts_url").takeIf { it.isNotBlank() }
                ?.let { TursoConfig(it, get("facts_token")) },
            pandastack = get("panda_key").takeIf { it.isNotBlank() }?.let {
                PandaStackConfig(
                    apiUrl = get("panda_url").ifBlank { pandaDefault.apiUrl },
                    apiKey = it,
                    template = get("panda_template").ifBlank { pandaDefault.template },
                )
            },
            knowledgeDb = get("knowledge_url").takeIf { it.isNotBlank() }
                ?.let { TursoConfig(it, get("knowledge_token")) },
        )
    }

    override fun save(settings: AppSettings) {
        prefs.edit()
            .putString("router_url", settings.router.baseUrl)
            .putString("router_key", settings.router.apiKey)
            .putString("router_model", settings.router.defaultModel)
            .putString("search_model", settings.router.webSearchModel)
            .putString("journal_url", settings.journalDb?.url.orEmpty())
            .putString("journal_token", settings.journalDb?.authToken.orEmpty())
            .putString("facts_url", settings.factsDb?.url.orEmpty())
            .putString("facts_token", settings.factsDb?.authToken.orEmpty())
            .putString("knowledge_url", settings.knowledgeDb?.url.orEmpty())
            .putString("knowledge_token", settings.knowledgeDb?.authToken.orEmpty())
            .putString("panda_url", settings.pandastack?.apiUrl.orEmpty())
            .putString("panda_key", settings.pandastack?.apiKey.orEmpty())
            .putString("panda_template", settings.pandastack?.template.orEmpty())
            .apply()
    }
}
