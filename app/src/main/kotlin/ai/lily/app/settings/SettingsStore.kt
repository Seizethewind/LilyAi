package ai.lily.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.lily.app.gateway.EmbeddingEndpoint
import ai.lily.app.gateway.RouterConfig
import ai.lily.app.gateway.RouterKind
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig
import ai.lily.app.workers.WorkerProfiles

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
                // Settings saved before these two existed have neither: no name, and the first router is FreeLLMAPI.
                name = get("router_name"),
                kind = RouterKind.fromId(get("router_kind"), RouterKind.FreeLlmApi),
            ),
            router2 = get("router2_url").takeIf { it.isNotBlank() }?.let {
                RouterConfig(
                    baseUrl = it,
                    apiKey = get("router2_key"),
                    defaultModel = d.defaultModel,
                    webSearchModel = d.webSearchModel,
                    name = get("router2_name"),
                    kind = RouterKind.fromId(get("router2_kind"), RouterKind.NineRouter),
                )
            },
            workers = WorkerProfiles.fromJson(get("workers_json")),
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
            // A saved value that no longer passes the endpoint's own checks (for example a blank model) counts as not set.
            embedding = get("embed_url").takeIf { it.isNotBlank() }?.let { url ->
                val model = get("embed_model")
                runCatching {
                    EmbeddingEndpoint(
                        name = "embedding",
                        baseUrl = url,
                        apiKey = get("embed_key"),
                        model = model,
                        tag = get("embed_tag").ifBlank { EmbeddingEndpoint.defaultTag(model, null) },
                    )
                }.getOrNull()
            },
            verifyCommands = get("verify_commands").lines().map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    override fun save(settings: AppSettings) {
        prefs.edit()
            .putString("router_url", settings.router.baseUrl)
            .putString("router_key", settings.router.apiKey)
            .putString("router_model", settings.router.defaultModel)
            .putString("search_model", settings.router.webSearchModel)
            .putString("router_name", settings.router.name)
            .putString("router_kind", settings.router.kind.id)
            .putString("router2_url", settings.router2?.baseUrl.orEmpty())
            .putString("router2_key", settings.router2?.apiKey.orEmpty())
            .putString("router2_name", settings.router2?.name.orEmpty())
            .putString("router2_kind", settings.router2?.kind?.id.orEmpty())
            .putString("workers_json", WorkerProfiles.toJson(settings.workers))
            .putString("journal_url", settings.journalDb?.url.orEmpty())
            .putString("journal_token", settings.journalDb?.authToken.orEmpty())
            .putString("facts_url", settings.factsDb?.url.orEmpty())
            .putString("facts_token", settings.factsDb?.authToken.orEmpty())
            .putString("knowledge_url", settings.knowledgeDb?.url.orEmpty())
            .putString("knowledge_token", settings.knowledgeDb?.authToken.orEmpty())
            .putString("panda_url", settings.pandastack?.apiUrl.orEmpty())
            .putString("panda_key", settings.pandastack?.apiKey.orEmpty())
            .putString("panda_template", settings.pandastack?.template.orEmpty())
            .putString("embed_url", settings.embedding?.baseUrl.orEmpty())
            .putString("embed_key", settings.embedding?.apiKey.orEmpty())
            .putString("embed_model", settings.embedding?.model.orEmpty())
            .putString("embed_tag", settings.embedding?.tag.orEmpty())
            .putString("verify_commands", settings.verifyCommands.joinToString("\n"))
            .apply()
    }
}
