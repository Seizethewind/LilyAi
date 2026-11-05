package ai.lily.app.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

    override fun load(): AppSettings = SettingsCodec.read(::get)

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
            .putString("lily_name", settings.lily.name)
            .putString("lily_instructions", settings.lily.instructions)
            .putString("lily_router", settings.lily.router.toString())
            .putString("lily_model", settings.lily.model)
            .putString("lily_photo", settings.lily.photoPath)
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
