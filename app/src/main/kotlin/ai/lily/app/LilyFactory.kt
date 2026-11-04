package ai.lily.app

import ai.lily.app.codeindex.DiskCodeFiles
import ai.lily.app.codeindex.WorkspaceIndex
import ai.lily.app.core.Context
import ai.lily.app.core.Lily
import ai.lily.app.core.LlmPlanner
import ai.lily.app.core.Memory
import ai.lily.app.core.WorkerManager
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.RouterModelGateway
import ai.lily.app.memory.TursoClient
import ai.lily.app.memory.TursoJournal
import ai.lily.app.memory.TursoProvenFacts
import ai.lily.app.plugins.PluginHost
import ai.lily.app.sandbox.PandaStackClient
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
import ai.lily.app.workers.ManagerWorker
import ai.lily.app.workers.ModelWorker
import ai.lily.app.workers.ProfileGateway
import ai.lily.app.workers.ResearchWorker
import ai.lily.app.workers.Worker
import ai.lily.app.workers.WorkerRoster
import java.io.File

/** Wires everything together from the user's settings. */
object LilyFactory {
    /**
     * [editor] is how the coding and file workers see and change the files open in the code editor. Without it they only answer.
     * [workspaceRoot] is the project folder; with it the coding worker can find relevant files beyond the open tabs.
     * [plugins] adds the tools of every plugin that is on and allowed (for example the optional Termux plugin). The app's
     * own tools are registered first, so a plugin can never replace one of them.
     * [roster] is the list of created workers that Lily's own TEAM worker reads and changes. Without it there is no TEAM worker.
     * Workers the person created (settings.workers) take over their role from the built-in worker, each with its own router,
     * model and instructions. A role with no created workers keeps the built-in one.
     */
    fun create(
        settings: AppSettings,
        editor: EditorBridge? = null,
        workspaceRoot: File? = null,
        plugins: PluginHost? = null,
        roster: WorkerRoster? = null,
    ): Lily {
        val gateway = RouterModelGateway(settings.router)
        val second: ModelGateway? = settings.router2?.let { RouterModelGateway(it) }

        val tools = ToolSystem().apply {
            register(WebSearchTool(gateway, settings.router.webSearchModel))
            settings.pandastack?.let { register(SandboxTool(PandaStackClient(it))) }
            plugins?.registerInto(this)
        }

        val workspace = workspaceRoot?.let { WorkspaceIndex(DiskCodeFiles(it)) }

        fun worker(type: WorkerType, g: ModelGateway): Worker = when (type) {
            WorkerType.CODING -> CodingWorker(g, tools, editor, workspace)
            WorkerType.RESEARCH -> ResearchWorker(g, tools)
            WorkerType.BUILD -> BuildWorker(g, tools)
            WorkerType.GIT -> GitWorker(g, tools)
            WorkerType.FILE -> FileWorker(g, tools, editor)
            WorkerType.GENERAL -> GeneralWorker(g, tools)
            WorkerType.TEST -> ModelWorker(WorkerType.TEST, g, tools)
            WorkerType.TEAM -> ManagerWorker(g, tools, checkNotNull(roster) { "The TEAM worker needs a roster" })
        }

        val created = settings.workers.filter { it.enabled }.groupBy { it.role }
        val manager = WorkerManager().apply {
            for (type in WorkerType.values()) {
                // Lily's own TEAM role manages the roster, so without one there is nothing for it to do.
                if (type == WorkerType.TEAM && roster == null) continue
                val profiles = created[type].orEmpty()
                if (profiles.isEmpty()) {
                    // Testers exist only when the person makes them; every other role always has a built-in worker.
                    if (type != WorkerType.TEST) register(worker(type, gateway))
                } else {
                    for (p in profiles) {
                        val router = if (p.router == 1 && second != null) second else gateway
                        register(worker(type, ProfileGateway(router, p.model, p.instructions)))
                    }
                }
            }
        }

        val journalDb = settings.journalDb
        val factsDb = settings.factsDb
        val memory = if (journalDb != null && factsDb != null) {
            Memory(TursoJournal(TursoClient(journalDb)), TursoProvenFacts(TursoClient(factsDb)))
        } else null

        return Lily(LlmPlanner(gateway), manager, Context(), memory)
    }
}
