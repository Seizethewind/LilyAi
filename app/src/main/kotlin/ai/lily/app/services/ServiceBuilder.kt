package ai.lily.app.services

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.RouterEmbedder
import ai.lily.app.memory.KnowledgeSchema
import ai.lily.app.memory.TursoClient
import ai.lily.app.research.GuardedPageFetcher
import ai.lily.app.research.ModelSearchProvider
import ai.lily.app.research.UrlConnectionTransport
import ai.lily.app.research.UrlGuard
import ai.lily.app.sandbox.DiskVerifyFiles
import ai.lily.app.sandbox.PandaStackClient
import ai.lily.app.sandbox.SandboxVerifier
import ai.lily.app.settings.AppSettings
import java.io.File

/** Whether the knowledge layer can be used with the current settings. */
sealed interface KnowledgeStatus {
    class Ready(val services: KnowledgeServices) : KnowledgeStatus

    /** No knowledge database has been entered in Settings. */
    object NotConfigured : KnowledgeStatus

    /** The settings cannot be used; [message] says why and is fit to show. */
    class Problem(val message: String) : KnowledgeStatus
}

/** Builds the app's services from the settings. Nothing here talks to a network. */
object ServiceBuilder {
    fun knowledge(settings: AppSettings, gateway: ModelGateway): KnowledgeStatus {
        val config = settings.knowledgeDb ?: return KnowledgeStatus.NotConfigured
        val configProblem = KnowledgeSchema.configProblem(settings)
        if (configProblem != null) return KnowledgeStatus.Problem(configProblem)

        var embedding: EmbeddingSetup? = null
        val embeddingSettings = settings.embedding
        if (embeddingSettings != null) {
            val problem = embeddingSettings.problem()
            if (problem != null) return KnowledgeStatus.Problem(problem)
            embedding = EmbeddingSetup(RouterEmbedder(embeddingSettings.endpoints()), embeddingSettings.effectiveTag)
        }

        return KnowledgeStatus.Ready(
            KnowledgeServices.build(
                db = TursoClient(config),
                gateway = gateway,
                embedding = embedding,
                model = settings.router.defaultModel,
                searchProvider = ModelSearchProvider(gateway, settings.router.webSearchModel),
                fetcher = GuardedPageFetcher(UrlGuard(), UrlConnectionTransport()),
            ),
        )
    }

    /**
     * The sandbox verifier for the project in [root], or null when no Pandastack key is set. Pass `settings.verifyChecks` as
     * `customChecks` when calling `verify`; an empty list means the checks are detected from the project.
     */
    fun verifier(settings: AppSettings, root: File): SandboxVerifier? =
        settings.pandastack?.let { SandboxVerifier(PandaStackClient(it), DiskVerifyFiles(root)) }
}
