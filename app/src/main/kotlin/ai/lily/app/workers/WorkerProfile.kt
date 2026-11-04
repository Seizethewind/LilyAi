package ai.lily.app.workers

import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.ModelResponse
import org.json.JSONArray
import org.json.JSONObject

/**
 * One worker the person (or Lily) created: what it is for, which router and model it uses, and the instructions that
 * shape its answers. [router] is 0 for the first router and 1 for the second. Several workers can share a role.
 */
data class WorkerProfile(
    val id: String,
    val name: String,
    val role: WorkerType,
    val router: Int,
    val model: String,
    val instructions: String,
    val enabled: Boolean = true,
)

/** A starting point for a worker: a role and the instructions that fit it. */
data class WorkerTemplate(val label: String, val role: WorkerType, val instructions: String)

object WorkerTemplates {
    val all = listOf(
        WorkerTemplate(
            "Tester", WorkerType.TEST,
            "You are a software tester. Look for bugs, missing edge cases and wrong assumptions in the code or plan you are given. " +
                "Name each problem, say how to reproduce it, and suggest a test. Do not rewrite the code. If you find nothing, say so plainly.",
        ),
        WorkerTemplate(
            "Coder", WorkerType.CODING,
            "You are a careful senior developer. Make the smallest change that fully solves the task, match the existing style, " +
                "and keep error handling and validation.",
        ),
        WorkerTemplate(
            "Researcher", WorkerType.RESEARCH,
            "You are a researcher. Answer from sources, say which claims you are unsure about, and keep the answer short.",
        ),
        WorkerTemplate(
            "Builder", WorkerType.BUILD,
            "You handle builds, tests and shell commands. Prefer one short, safe command. Explain build errors in plain words.",
        ),
        WorkerTemplate(
            "Git helper", WorkerType.GIT,
            "You help with git. Give exact commands, and warn before anything that deletes or rewrites history.",
        ),
        WorkerTemplate(
            "File helper", WorkerType.FILE,
            "You help create, move and organise files in the project. Never touch files outside the project.",
        ),
        WorkerTemplate(
            "General", WorkerType.GENERAL,
            "You are a helpful assistant. Answer clearly and briefly.",
        ),
    )
}

/** Wraps a router gateway so one worker always uses its own model and instructions, whatever the worker code asks for. */
class ProfileGateway(
    private val inner: ModelGateway,
    private val model: String,
    private val instructions: String,
) : ModelGateway {
    override suspend fun complete(request: ModelRequest): ModelResponse {
        val system = listOfNotNull(instructions.takeIf { it.isNotBlank() }, request.system).joinToString("\n\n")
        return inner.complete(request.copy(model = model.ifBlank { request.model }, system = system.ifBlank { null }))
    }
}

/** Saves the worker list as JSON. Reading is forgiving: an entry that is broken is skipped rather than losing the rest. */
object WorkerProfiles {
    fun toJson(list: List<WorkerProfile>): String = JSONArray().also { arr ->
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name).put("role", p.role.name).put("router", p.router)
                    .put("model", p.model).put("instructions", p.instructions).put("enabled", p.enabled),
            )
        }
    }.toString()

    fun fromJson(text: String): List<WorkerProfile> {
        if (text.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                WorkerProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    role = WorkerType.valueOf(o.getString("role")),
                    router = o.optInt("router", 0).coerceIn(0, 1),
                    model = o.optString("model", ""),
                    instructions = o.optString("instructions", ""),
                    enabled = o.optBoolean("enabled", true),
                )
            }.getOrNull()
        }
    }
}
