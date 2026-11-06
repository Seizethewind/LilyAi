package ai.lily.app.workers

import ai.lily.app.core.Context
import ai.lily.app.core.Task
import ai.lily.app.core.TaskResult
import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.RouterModels
import ai.lily.app.plugins.ConfirmRequest
import ai.lily.app.plugins.ConfirmationGate
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
    /**
     * [routers] holds 0 and/or 1; [filter] keeps only model ids containing that text; [model] is one exact id Lily picked,
     * used instead of the filter when a router offers it. [icon] and [color] are [WorkerLook] keys, blank when Lily gave
     * none or an unknown one, which means the role's own look.
     */
    data class Create(
        val name: String,
        val role: WorkerType,
        val count: Int,
        val instructions: String,
        val filter: String,
        val routers: List<Int>,
        val icon: String = "",
        val color: String = "",
        val model: String = "",
    ) : TeamAction

    /** Changes only the fields that are given, on the workers Lily made whose name matches. [router] is 0 or 1, null when not given. */
    data class Update(
        val name: String,
        val instructions: String = "",
        val model: String = "",
        val router: Int? = null,
        val icon: String = "",
        val color: String = "",
    ) : TeamAction

    data class Dismiss(val name: String) : TeamAction
}

/** Reads and carries out Lily's changes to her team. Only workers marked byLily can be changed or dismissed, and the team has a size limit. */
object WorkerTeam {
    const val MAX_WORKERS = 40
    const val MAX_PER_ACTION = 20

    /** The actions in a model reply, or null when there is no readable list in it. */
    fun parse(text: String): List<TeamAction>? = runCatching {
        val arr = JSONArray(text.substring(text.indexOf('['), text.lastIndexOf(']') + 1))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            fun icon() = o.optString("icon").trim().lowercase().takeIf { it in WorkerLook.icons }.orEmpty()
            fun color() = o.optString("color").trim().lowercase().takeIf { it in WorkerLook.colors }.orEmpty()
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
                        icon = icon(),
                        color = color(),
                        model = o.optString("model").trim(),
                    )
                }
                "update" -> TeamAction.Update(
                    name = o.getString("name"),
                    instructions = o.optString("instructions").trim().take(2000),
                    model = o.optString("model").trim(),
                    router = when (o.optInt("router", 0)) {
                        1 -> 0
                        2 -> 1
                        else -> null
                    },
                    icon = icon(),
                    color = color(),
                ).takeIf { it.instructions.isNotEmpty() || it.model.isNotEmpty() || it.router != null || it.icon.isNotEmpty() || it.color.isNotEmpty() }
                "dismiss" -> TeamAction.Dismiss(o.getString("name"))
                else -> null
            }
        }
    }.getOrNull()

    /** What the person is asked to approve: one line per change, in plain words, with control characters made harmless. */
    fun describe(actions: List<TeamAction>): List<Pair<String, String>> = actions.map { a ->
        when (a) {
            is TeamAction.Create -> {
                val parts = listOfNotNull(
                    a.model.takeIf { it.isNotBlank() }?.let { "model $it" },
                    a.filter.takeIf { it.isNotBlank() }?.let { "models containing '$it'" },
                    a.icon.takeIf { it.isNotBlank() }?.let { "icon $it" },
                    a.color.takeIf { it.isNotBlank() }?.let { "colour $it" },
                    a.instructions.takeIf { it.isNotBlank() }?.let { "rules: ${it.take(120)}" },
                )
                "Create" to shown("${a.count} x ${a.role.name.lowercase()} '${a.name}'" + parts.joinToString("") { " · $it" })
            }
            is TeamAction.Update -> {
                val parts = listOfNotNull(
                    a.instructions.takeIf { it.isNotBlank() }?.let { "rules: ${it.take(120)}" },
                    a.router?.let { "router ${it + 1}" },
                    a.model.takeIf { it.isNotBlank() }?.let { "model $it" },
                    a.icon.takeIf { it.isNotBlank() }?.let { "icon $it" },
                    a.color.takeIf { it.isNotBlank() }?.let { "colour $it" },
                )
                "Change" to shown("'${a.name}': ${parts.joinToString("; ")}")
            }
            is TeamAction.Dismiss -> "Dismiss" to shown(if (a.name.trim() == "*") "every worker Lily made" else "'${a.name}'")
        }
    }

    private fun shown(text: String): String = text.replace(Regex("\\p{Cntrl}"), " ").take(300)

    /** The model ids Lily may name, for her prompt: at most [perRouter] per router, each cut to 70 characters. [lists] is keyed by 1 and 2, as Lily writes them. */
    fun modelLines(lists: Map<Int, List<String>>, perRouter: Int = 40): String = lists.entries
        .filter { it.value.isNotEmpty() }
        .joinToString("\n") { (router, ids) ->
            "Router $router: " + ids.take(perRouter).joinToString(", ") { it.take(70) } +
                if (ids.size > perRouter) " ... and ${ids.size - perRouter} more (modelFilter finds them)" else ""
        }
        .ifBlank { "(no models are loaded: leave model out)" }

    private fun named(workers: List<WorkerProfile>, name: String): List<WorkerProfile> {
        val key = name.trim().lowercase()
        return workers.filter { key == "*" || it.name.lowercase() == key || it.name.lowercase().startsWith("$key ") }
    }

    /** Applies the actions, saves the result once, and says in plain words what happened. */
    suspend fun apply(actions: List<TeamAction>, roster: WorkerRoster): String {
        var workers = roster.list()
        val before = workers
        val notes = mutableListOf<String>()
        val models = mutableMapOf<Int, List<String>>()
        suspend fun modelsOf(router: Int): List<String> = models.getOrPut(router) { roster.models(router) }

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
                        r to modelsOf(r).filter { filter.isEmpty() || it.lowercase().contains(filter) }
                    }
                    val picked = action.model.takeIf { it.isNotBlank() }?.let { wanted ->
                        action.routers.firstNotNullOfOrNull { r -> modelsOf(r).firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { r to it } }
                    }
                    // Alternate between the routers so the team is spread over both.
                    val choices = if (picked != null) {
                        listOf(picked)
                    } else {
                        (0 until (pools.maxOfOrNull { it.second.size } ?: 0)).flatMap { i ->
                            pools.mapNotNull { (r, list) -> list.getOrNull(i)?.let { r to it } }
                        }
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
                            iconKey = action.icon,
                            colorKey = action.color,
                        )
                    }
                    workers = workers + made
                    notes += "Created ${made.size} ${action.role.name.lowercase()} worker(s): ${made.joinToString { it.name }}. " +
                        if (choices.isEmpty()) "No models were found, so they use the router's default." else "They use ${minOf(n, choices.size)} different model(s)."
                    if (action.model.isNotBlank() && picked == null) notes += "The model '${action.model}' is not offered by that router, so I chose by the filter instead."
                }
                is TeamAction.Update -> {
                    val matching = named(workers, action.name)
                    val mine = matching.filter { it.byLily }
                    if (mine.isEmpty()) {
                        notes += if (matching.isEmpty()) "No worker is called '${action.name}'." else "'${action.name}' was made by you, so I left it. You can change it in the AI section."
                        continue
                    }
                    var unknownModel = false
                    val changed = mine.map { p ->
                        var router = action.router ?: p.router
                        var model = p.model
                        if (action.model.isNotBlank()) {
                            // On the router Lily named; otherwise on the worker's own router first, then the other one.
                            val where = if (action.router != null) listOf(action.router) else listOf(p.router, 1 - p.router)
                            val hit = where.firstNotNullOfOrNull { r -> modelsOf(r).firstOrNull { it.equals(action.model, ignoreCase = true) }?.let { r to it } }
                            if (hit != null) {
                                router = hit.first
                                model = hit.second
                            } else {
                                unknownModel = true
                            }
                        } else if (router != p.router) {
                            // The old model may not exist on the other router, so the worker goes back to that router's default.
                            model = ""
                        }
                        p.copy(
                            router = router,
                            model = model,
                            instructions = action.instructions.ifBlank { p.instructions },
                            iconKey = action.icon.ifBlank { p.iconKey },
                            colorKey = action.color.ifBlank { p.colorKey },
                        )
                    }
                    workers = workers.map { w -> changed.firstOrNull { it.id == w.id } ?: w }
                    notes += "Changed ${changed.size}: ${changed.joinToString { it.name }}." +
                        (if (unknownModel) " The model '${action.model}' is not offered, so the model was left as it was." else "") +
                        (if (matching.size > mine.size) " I left the ones you made." else "")
                }
                is TeamAction.Dismiss -> {
                    val matching = named(workers, action.name)
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

/**
 * Lily's own role: creates, changes and dismisses the other workers when asked, or when a job needs a team. With a [gate]
 * the person approves the list of changes first; without one the changes are made at once.
 */
class ManagerWorker(
    gateway: ModelGateway,
    tools: ToolSystem,
    private val roster: WorkerRoster,
    private val gate: ConfirmationGate? = null,
) : Worker(WorkerType.TEAM, gateway, tools) {

    override suspend fun run(task: Task, context: Context): TaskResult {
        val current = roster.list().joinToString("\n") {
            "- ${it.name} (${it.role.name.lowercase()}, made by ${if (it.byLily) "you" else "the person"})"
        }.ifBlank { "(none)" }
        val roles = WorkerType.values().filter { it != WorkerType.TEAM }.joinToString { it.name }
        val offered = WorkerTeam.modelLines(mapOf(1 to roster.models(0), 2 to roster.models(1)))
        val system = "You manage a team of workers for the person. Reply with ONLY a JSON array of actions. " +
            "Create: {\"action\":\"create\",\"name\":\"Tester\",\"role\":\"TEST\",\"count\":3,\"instructions\":\"...\",\"router\":0,\"modelFilter\":\"free\",\"icon\":\"check\",\"color\":\"pad\"}. " +
            "role is one of $roles. instructions are short rules for that job (under 600 characters). " +
            "router 0 uses both routers, 1 or 2 uses only that one. modelFilter is optional: only models whose id contains it are used. " +
            "To choose one model yourself, add \"model\" with its exact id from the lists below. " +
            "icon is optional and one of ${WorkerLook.icons.joinToString()}; color is optional and one of ${WorkerLook.colors.joinToString()}. " +
            "Leave them out to give the worker its role's own look. " +
            "Update: {\"action\":\"update\",\"name\":\"Tester\",\"instructions\":\"...\",\"model\":\"<exact id>\",\"router\":1,\"icon\":\"star\",\"color\":\"coral\"} " +
            "changes only the fields you give, on the workers you made whose name is that or starts with it. " +
            "Dismiss: {\"action\":\"dismiss\",\"name\":\"Tester\"} removes the workers you made whose name is that or starts with it; \"*\" removes all you made. " +
            "You cannot change or dismiss workers the person made.\n" +
            "Models you can name:\n$offered\n" +
            "Current workers:\n$current"
        val reply = gateway.complete(ModelRequest(task.description, system = system, model = WorkerModels.forWorker(type))).text
        val actions = WorkerTeam.parse(reply) ?: return TaskResult(task.id, "I could not turn that into changes to the team.", false)
        if (actions.isEmpty()) return TaskResult(task.id, "Nothing to change.", true)
        if (gate != null) {
            val request = ConfirmRequest("lily.team", "Lily's team", "change workers", "this app", WorkerTeam.describe(actions))
            if (!gate.confirm(request)) return TaskResult(task.id, "You did not approve the changes, so the team is unchanged.", true)
        }
        return TaskResult(task.id, WorkerTeam.apply(actions, roster), true)
    }
}
