package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.RouterModels
import ai.lily.app.settings.SettingsStore
import ai.lily.app.tools.ToolSystem
import org.json.JSONArray

/** The list of created workers, as Lily's team manager sees it. */
interface WorkerRoster {
    fun list(): List<WorkerProfile>

    /** Replaces the whole list. */
    fun save(workers: List<WorkerProfile>)

    /** The model ids router 0 or 1 offers; empty when it is not set or cannot be reached. */
    suspend fun models(router: Int): List<String>
}

/** The roster kept in the app's settings. [onChanged] runs after every save so Lily is rebuilt with the new team. */
class StoreRoster(private val store: SettingsStore, private val onChanged: () -> Unit) : WorkerRoster {
    override fun list(): List<WorkerProfile> = store.load().workers

    override fun save(workers: List<WorkerProfile>) {
        store.save(store.load().copy(workers = workers))
        onChanged()
    }

    override suspend fun models(router: Int): List<String> {
        val settings = store.load()
        val config = if (router == 1) settings.router2 else settings.router
        return if (config == null) emptyList() else RouterModels.list(config).getOrDefault(emptyList())
    }
}

sealed interface TeamAction {
    /** [routers] holds 0 and/or 1; [filter] keeps only model ids containing that text. */
    data class Create(
        val name: String,
        val role: WorkerType,
        val count: Int,
        val instructions: String,
        val filter: String,
        val routers: List<Int>,
    ) : TeamAction

    data class Dismiss(val name: String) : TeamAction
}

/** Reads and carries out Lily's changes to her team. Only workers marked byLily can be dismissed, and the team has a size limit. */
object WorkerTeam {
    const val MAX_WORKERS = 40
    const val MAX_PER_ACTION = 20

    /** The actions in a model reply, or null when there is no readable list in it. */
    fun parse(text: String): List<TeamAction>? = runCatching {
        val arr = JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            when (o.optString("action").lowercase()) {
                "create" -> {
                    val role = runCatching { WorkerType.valueOf(o.getString("role").uppercase()) }.getOrNull()
                        ?.takeIf { it != WorkerType.TEAM } ?: return@mapNotNull null
                    TeamAction.Create(
                        name = o.optString("name").ifBlank { role.name.lowercase() }.trim().take(40),
                        role = role,
                        count = o.optInt("count", 1).coerceIn(1, MAX_PER_ACTION),
                        instructions = o.optString("instructions").trim().take(2000),
                        filter = o.optString("modelFilter").trim(),
                        // 1 or 2 names one router; anything else means both.
                        routers = when (o.optInt("router", 0)) {
                            1 -> listOf(0)
                            2 -> listOf(1)
                            else -> listOf(0, 1)
                        },
                    )
                }
                "dismiss" -> TeamAction.Dismiss(o.getString("name"))
                else -> null
            }
        }
    }.getOrNull()

    /** Applies the actions, saves the result once, and says in plain words what happened. */
    suspend fun apply(actions: List<TeamAction>, roster: WorkerRoster): String {
        var workers = roster.list()
        val before = workers
        val notes = mutableListOf<String>()
        val models = mutableMapOf<Int, List<String>>()

        for (action in actions) {
            when (action) {
                is TeamAction.Create -> {
                    val n = minOf(action.count, MAX_WORKERS - workers.size)
                    if (n <= 0) {
                        notes += "Not created: there are already $MAX_WORKERS workers."
                        continue
                    }
                    val filter = action.filter.lowercase()
                    val pools = action.routers.map { r ->
                        r to models.getOrPut(r) { roster.models(r) }.filter { filter.isEmpty() || it.lowercase().contains(filter) }
                    }
                    // Alternate between the routers so the team is spread over both.
                    val choices = (0 until (pools.maxOfOrNull { it.second.size } ?: 0)).flatMap { i ->
                        pools.mapNotNull { (r, list) -> list.getOrNull(i)?.let { r to it } }
                    }
                    val instructions = action.instructions.ifBlank {
                        WorkerTemplates.all.firstOrNull { it.role == action.role }?.instructions.orEmpty()
                    }
                    val used = workers.map { it.name.lowercase() }.toMutableSet()
                    var number = 1
                    fun nextName(): String {
                        while (true) {
                            val candidate = if (n == 1 && number == 1) action.name else "${action.name} $number"
                            number++
                            if (used.add(candidate.lowercase())) return candidate
                        }
                    }
                    val made = (0 until n).map { i ->
                        val (router, model) = if (choices.isEmpty()) action.routers.first() to "" else choices[i % choices.size]
                        WorkerProfile(
                            id = java.util.UUID.randomUUID().toString(),
                            name = nextName(),
                            role = action.role,
                            router = router,
                            model = model,
                            instructions = instructions,
                            byLily = true,
                        )
                    }
                    workers = workers + made
                    notes += "Created ${made.size} ${action.role.name.lowercase()} worker(s): ${made.joinToString { it.name }}. " +
                        if (choices.isEmpty()) "No models were found, so they use the router's default." else "They use ${minOf(n, choices.size)} different model(s)."
                }
                is TeamAction.Dismiss -> {
                    val key = action.name.trim().lowercase()
                    val matching = workers.filter { key == "*" || it.name.lowercase() == key || it.name.lowercase().startsWith("$key ") }
                    val mine = matching.filter { it.byLily }.toSet()
                    workers = workers.filter { it !in mine }
                    notes += when {
                        matching.isEmpty() -> "No worker is called '${action.name}'."
                        mine.isEmpty() -> "'${action.name}' was made by you, so I left it. You can remove it in the AI section."
                        else -> "Dismissed ${mine.size}: ${mine.joinToString { it.name }}." +
                            if (matching.size > mine.size) " I left the ones you made." else ""
                    }
                }
            }
        }
        if (workers != before) {
            roster.save(workers)
            notes += "The changes are available from my next answer."
        }
        return notes.joinToString("\n")
    }
}

/** Lily's own role: creates and dismisses the other workers when asked, or when a job needs a team. */
class ManagerWorker(gateway: ModelGateway, tools: ToolSystem, private val roster: WorkerRoster) :
    Worker(WorkerType.TEAM, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        val current = roster.list().joinToString("\n") {
            "- ${it.name} (${it.role.name.lowercase()}, made by ${if (it.byLily) "you" else "the person"})"
        }.ifBlank { "(none)" }
        val roles = WorkerType.values().filter { it != WorkerType.TEAM }.joinToString { it.name }
        val system = "You manage a team of workers for the person. Reply with ONLY a JSON array of actions. " +
            "Create: {\"action\":\"create\",\"name\":\"Tester\",\"role\":\"TEST\",\"count\":3,\"instructions\":\"...\",\"router\":0,\"modelFilter\":\"free\"}. " +
            "role is one of $roles. instructions are short rules for that job (under 600 characters). " +
            "router 0 uses both routers, 1 or 2 uses only that one. modelFilter is optional: only models whose id contains it are used. " +
            "Dismiss: {\"action\":\"dismiss\",\"name\":\"Tester\"} removes the workers you made whose name is that or starts with it; \"*\" removes all you made. " +
            "You cannot dismiss workers the person made. Current workers:\n$current"
        val reply = gateway.complete(ModelRequest(task.description, system = system, model = WorkerModels.forWorker(type))).text
        val actions = WorkerTeam.parse(reply) ?: return TaskResult(task.id, "I could not turn that into changes to the team.", false)
        if (actions.isEmpty()) return TaskResult(task.id, "Nothing to change.", true)
        return TaskResult(task.id, WorkerTeam.apply(actions, roster), true)
    }
}
