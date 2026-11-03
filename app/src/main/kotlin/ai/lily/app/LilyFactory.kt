package ai.lily.app

import ai.lily.app.codeindex.DiskCodeFiles
import ai.lily.app.codeindex.WorkspaceIndex
import ai.lily.app.core.Context
import ai.lily.app.core.Lily
import ai.lily.app.core.LlmPlanner
import ai.lily.app.core.Memory
import ai.lily.app.core.WorkerManager
import ai.lily.app.gateway.RouterModelGateway
import ai.lily.app.memory.TursoClient
import ai.lily.app.memory.TursoJournal
import ai.lily.app.memory.TursoProvenFacts
import ai.lily.app.plugins.PluginHost
import ai.lily.app.sandbox.PandaStackClient
import ai.lily.app.services.KnowledgeServices
import ai.lily.app.services.KnowledgeStatus
import ai.lily.app.services.ServiceBuilder
import ai.lily.app.settings.AppSettings
import ai.lily.app.tools.EditorBridge
import ai.lily.app.tools.SandboxTool
import ai.lily.app.tools.ToolSystem
import ai.lily.app.tools.WebSearchTool
import ai.lily.app.workers.BuildWorker
import ai.lily.app.workers.CodingWorker
import ai.lily.app.workers.FileWorker
import ai.lily.app.workers.GeneralWorker
import ai.lily.app.workers.GitWorker
import ai.lily.app.workers.ResearchWorker
import java.io.File

/** Wires everything together from the user's settings. */
object LilyFactory {
    /**
     * [editor] is how the coding and file workers see and change the files open in the code editor. Without it they only answer.
     * [workspaceRoot] is the project folder; with it the coding worker can find relevant files beyond the open tabs.
     * [plugins] adds the tools of every plugin that is on and allowed (for example the optional Termux plugin). The app's
     * own tools are registered first, so a plugin can never replace one of them.
     * [knowledge] is the knowledge layer. When `settings.researchMode` is on, the research worker uses its research runner. If
     * it is not given, it is built from the settings (the knowledge database must be set, see [ServiceBuilder.knowledge]). With
     * research mode off, or no usable knowledge database, the research worker behaves as before.
     */
    fun create(
        settings: AppSettings,
        editor: EditorBridge? = null,
        workspaceRoot: File? = null,
        plugins: PluginHost? = null,
        knowledge: KnowledgeServices? = null,
    ): Lily {
        val gateway = RouterModelGateway(settings.router)

        val tools = ToolSystem().apply {
            register(WebSearchTool(gateway, settings.router.webSearchModel))
            settings.pandastack?.let { register(SandboxTool(PandaStackClient(it))) }
            plugins?.registerInto(this)
        }

        val research = if (settings.researchMode) {
            val services = knowledge ?: (ServiceBuilder.knowledge(settings, gateway) as? KnowledgeStatus.Ready)?.services
            services?.research
        } else {
            null
        }

        val workspace = workspaceRoot?.let { WorkspaceIndex(DiskCodeFiles(it)) }

        val manager = WorkerManager().apply {
            register(CodingWorker(gateway, tools, editor, workspace))
            register(ResearchWorker(gateway, tools, research))
            register(BuildWorker(gateway, tools))
            register(GitWorker(gateway, tools))
            register(FileWorker(gateway, tools, editor))
            register(GeneralWorker(gateway, tools))
        }

        val journalDb = settings.journalDb
        val factsDb = settings.factsDb
        val memory = if (journalDb != null && factsDb != null) {
            Memory(TursoJournal(TursoClient(journalDb)), TursoProvenFacts(TursoClient(factsDb)))
        } else null

        return Lily(LlmPlanner(gateway), manager, Context(), memory)
    }
}
