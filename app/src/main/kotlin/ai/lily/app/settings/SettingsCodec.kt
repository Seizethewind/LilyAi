package ai.lily.app.settings

import ai.lily.app.gateway.EmbeddingEndpoint
import ai.lily.app.gateway.RouterConfig
import ai.lily.app.gateway.RouterKind
import ai.lily.app.memory.TursoConfig
import ai.lily.app.sandbox.PandaStackConfig
import ai.lily.app.workers.WorkerProfiles

/** Turns saved key/value strings into [AppSettings]. Free of Android, so settings saved by older versions can be tested. */
object SettingsCodec {
    /** [get] returns "" for a key that was never saved. */
    fun read(get: (String) -> String): AppSettings {
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
            lily = LilyProfile.read(get),
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
}
