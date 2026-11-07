package ai.lily.app.workers

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelImage
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.gateway.ModelResponse
import ai.lily.app.plugins.ConfirmRequest
import ai.lily.app.plugins.ConfirmationGate
import ai.lily.app.tools.BrowserBridge
import ai.lily.app.tools.PageAction
import ai.lily.app.tools.PageElement
import ai.lily.app.tools.PageEvent
import ai.lily.app.tools.PageSnapshot

/** A browser the tests can steer: a fixed list of items, scripted results for actions, and a record of everything done to it. */
internal class FlowBrowser(
    private val items: List<PageElement> = listOf(
        PageElement(0, "input[email]", "Email", "hint \"you@example.com\""),
        PageElement(1, "button", "Sign in", ""),
    ),
    results: List<String> = emptyList(),
    var url: String = "https://x.test/",
    var cookies: Boolean = false,
    var driftOnAct: Boolean = false,
    var problems: List<PageEvent> = emptyList(),
    var shot: ModelImage? = null,
) : BrowserBridge {
    private val queue = ArrayDeque(results)
    val acted = mutableListOf<PageAction>()
    val locks = mutableListOf<String?>()
    val calls = mutableListOf<String>()
    val codes = mutableListOf<String>()

    override suspend fun show(): Boolean { calls += "show"; return true }
    override suspend fun currentUrl(): String = url
    override suspend fun open(url: String, timeoutMs: Long): Boolean { calls += "open $url"; this.url = url; return true }
    override suspend fun snapshot() = PageSnapshot(url, "Page", "Welcome")
    override suspend fun events(): List<PageEvent> = problems
    override suspend fun loadMillis(): Long? = 10L
    override suspend fun audit(): String? = null
    override suspend fun screenshot(code: String): ModelImage? { codes += code; return shot }
    override suspend fun elements(): List<PageElement>? = items
    override suspend fun act(action: PageAction): String {
        acted += action
        if (driftOnAct) url = "https://other.test/"
        return queue.removeFirstOrNull() ?: "ok"
    }
    override suspend fun hasCookies(url: String): Boolean = cookies
    override fun lockToHost(host: String?) { locks += host }
}

/** Answers from a script, in order, and keeps every request it was given. */
internal class ScriptedGateway(replies: List<String>) : ModelGateway {
    val requests = mutableListOf<ModelRequest>()
    private val queue = ArrayDeque(replies)
    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return ModelResponse(queue.removeFirstOrNull() ?: error("the script has no reply left"))
    }
}

/** A model that sees pictures or not, as the test needs: [onImage] answers requests that carry a picture, [onText] the rest. */
internal class VisionGateway(
    private val onImage: (ModelRequest) -> String,
    private val onText: (ModelRequest) -> String,
) : ModelGateway {
    val requests = mutableListOf<ModelRequest>()
    override suspend fun complete(request: ModelRequest): ModelResponse {
        requests += request
        return ModelResponse(if (request.images.isNotEmpty()) onImage(request) else onText(request))
    }
}

internal class RecordingGate(private val answer: Boolean) : ConfirmationGate {
    val asked = mutableListOf<ConfirmRequest>()
    override suspend fun confirm(request: ConfirmRequest): Boolean { asked += request; return answer }
}
