package ai.lily.app

import ai.lily.app.core.Context
import ai.lily.app.core.Lily
import ai.lily.app.core.LlmPlanner
import ai.lily.app.core.Memory
import ai.lily.app.core.WorkerManager
import ai.lily.app.gateway.RouterModelGateway
import ai.lily.app.memory.TursoClient
import ai.lily.app.memory.TursoJournal
import ai.lily.app.memory.TursoProvenFacts
import ai.lily.app.sandbox.PandaStackClient
import ai.lily.app.settings.AppSettings
import ai.lily.app.tools.SandboxTool
import ai.lily.app.tools.TerminalTool
import ai.lily.app.tools.ToolSystem
import ai.lily.app.tools.WebSearchTool
import ai.lily.app.workers.BuildWorker
import ai.lily.app.workers.CodingWorker
import ai.lily.app.workers.FileWorker
import ai.lily.app.workers.GeneralWorker
import ai.lily.app.workers.GitWorker
import ai.lily.app.workers.ResearchWorker

/** Wires everything together from the user's settings. */
object LilyFactory {
    fun create(settings: AppSettings): Lily {
        val gateway = RouterModelGateway(settings.router)

        val tools = ToolSystem().apply {
            register(TerminalTool())
            register(WebSearchTool(gateway, settings.router.webSearchModel))
            settings.pandastack?.let { register(SandboxTool(PandaStackClient(it))) }
        }

        val manager = WorkerManager().apply {
            register(CodingWorker(gateway, tools))
            register(ResearchWorker(gateway, tools))
            register(BuildWorker(gateway, tools))
            register(GitWorker(gateway, tools))
            register(FileWorker(gateway, tools))
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
