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
            embedding = get("embed_url").takeIf { it.isNotBlank() }?.let { url ->
                EmbeddingSettings(
                    model = get("embed_model"),
                    tag = get("embed_tag"),
                    dimensions = get("embed_dims").toIntOrNull(),
                    primary = EmbeddingServer(url, get("embed_key")),
                    fallback = get("embed2_url").takeIf { it.isNotBlank() }
                        ?.let { EmbeddingServer(it, get("embed2_key"), get("embed2_model")) },
                )
            },
            verifyChecks = CheckCommands.parse(get("verify_checks")),
            researchMode = get("research_mode") == "1",
        )
    }

    override fun save(settings: AppSettings) {
        val e = settings.embedding
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
            .putString("embed_model", e?.model.orEmpty())
            .putString("embed_tag", e?.tag.orEmpty())
            .putString("embed_dims", e?.dimensions?.toString().orEmpty())
            .putString("embed_url", e?.primary?.baseUrl.orEmpty())
            .putString("embed_key", e?.primary?.apiKey.orEmpty())
            .putString("embed2_url", e?.fallback?.baseUrl.orEmpty())
            .putString("embed2_model", e?.fallback?.model.orEmpty())
            .putString("embed2_key", e?.fallback?.apiKey.orEmpty())
            .putString("verify_checks", CheckCommands.format(settings.verifyChecks))
            .putString("research_mode", if (settings.researchMode) "1" else "")
            .apply()
    }
}
