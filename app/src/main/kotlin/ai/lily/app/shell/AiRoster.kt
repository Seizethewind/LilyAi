package ai.lily.app.shell

import ai.lily.app.workers.WorkerProfile
import ai.lily.app.workers.WorkerRoster

/**
 * Lily's team list, kept in the AI screen's own state. The AI screen holds its own copy of the settings, so a list written
 * straight to the store (StoreRoster) would be overwritten by the next edit there; going through [state] keeps one source of truth.
 * Each change is committed by [AiState], which also rebuilds Lily.
 */
class AiRoster(private val state: AiState) : WorkerRoster {
    override fun list(): List<WorkerProfile> = state.settings.workers

    override fun save(workers: List<WorkerProfile>) {
        val old = state.settings.workers.associateBy { it.id }
        val keep = workers.map { it.id }.toSet()
        old.keys.filter { it !in keep }.forEach(state::remove)
        workers.filter { old[it.id] != it }.forEach(state::upsert)
    }

    /** The models the router offers: the ones the AI screen already loaded, otherwise loaded now. Empty when it cannot be reached. */
    override suspend fun models(router: Int): List<String> {
        if (state.models[router].isNullOrEmpty()) state.loadModels(router)
        return state.models[router].orEmpty()
    }
}
