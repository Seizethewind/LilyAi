package com.seizethewind.lilyai.core

import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.api.ApiRegistry
import com.seizethewind.lilyai.api.ChatMessage
import com.seizethewind.lilyai.inference.LlamaEngine
import com.seizethewind.lilyai.memory.MemoryManager
import com.seizethewind.lilyai.prompt.PromptProcessor
import com.seizethewind.lilyai.safety.ConfirmationManager
import com.seizethewind.lilyai.safety.SafetyPolicy
import com.seizethewind.lilyai.settings.EngineMode
import com.seizethewind.lilyai.settings.SettingsManager
import com.seizethewind.lilyai.tools.ToolCallParser
import com.seizethewind.lilyai.tools.ToolExecutor
import com.seizethewind.lilyai.tools.ToolRegistry
import com.seizethewind.lilyai.tools.ToolRequest
import com.seizethewind.lilyai.tools.ToolResult
import java.util.concurrent.CopyOnWriteArrayList

/** Thrown/returned when the user pressed Stop. Not shown as an error. */
class StoppedException : Exception("Stopped.")

/** Lets the UI follow along while an answer is being produced. */
interface ResponseListener {
    fun onEngineChosen(name: String) {}
    fun onToken(text: String) {}
    fun onStatus(text: String) {}
}

/** Told when a request starts and ends, so the app can run a foreground service for long ones. */
interface TaskActivity {
    fun onBusy()
    fun onIdle()
}

/**
 * The assistant's brain: remembers things on request, picks the engine, lets the model use
 * tools through the safety policy, and can be stopped at any moment.
 */
class Agent(
    private val registry: ApiRegistry,
    private val settings: SettingsManager,
    private val localEngine: LlamaEngine,
    private val keyStore: ApiKeyStore,
    private val memory: MemoryManager,
    private val tools: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val safety: SafetyPolicy,
    private val confirmations: ConfirmationManager,
    private val activity: TaskActivity,
    private val prompts: PromptProcessor = PromptProcessor()
) {

    private val stopListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addStopListener(listener: () -> Unit) {
        stopListeners.add(listener)
    }

    fun removeStopListener(listener: () -> Unit) {
        stopListeners.remove(listener)
    }

    suspend fun respond(history: List<ChatMessage>, listener: ResponseListener): Result<String> {
        safety.newTask()
        activity.onBusy()
        try {
            return runTask(history, listener)
        } finally {
            activity.onIdle()
        }
    }

    /** The emergency stop: no more actions, no waiting approvals, local generation ends right away. */
    fun stop() {
        safety.halt()
        confirmations.cancelAll()
        localEngine.cancel()
        stopListeners.forEach { it() }
    }

    private suspend fun runTask(history: List<ChatMessage>, listener: ResponseListener): Result<String> {
        val lastUser = history.lastOrNull { it.role == "user" }?.content.orEmpty()

        // "remember ..." is handled directly and never sent to any model.
        memory.tryHandleCommand(lastUser)?.let { reply ->
            listener.onEngineChosen(MEMORY_NAME)
            return Result.success(reply)
        }

        val memoryLines = memory.contextLines(lastUser)
        val toolsSection = if (settings.toolsEnabled && !tools.isEmpty()) tools.promptSection() else null
        val conversation = history.toMutableList()

        for (step in 0..MAX_TOOL_STEPS) {
            val gate = ToolGate(listener)
            val generation = generate(conversation, memoryLines, toolsSection, gate)
            gate.finish()

            val text = generation.result.getOrElse { return generation.result }
            val call = if (toolsSection != null) ToolCallParser.parse(text) else null
            if (call == null) return generation.result
            if (step == MAX_TOOL_STEPS) {
                return Result.failure(IllegalStateException("The model kept asking for tools. Try rephrasing."))
            }

            val outcome = runTool(call, generation.cloud, listener)
            if (safety.halted) return Result.failure(StoppedException())
            conversation.add(ChatMessage("assistant", text))
            conversation.add(
                ChatMessage(
                    "user",
                    "[Tool result: " + call.name + "] " + outcome.output +
                        "\n(Now answer the user using this result.)"
                )
            )
        }
        return Result.failure(IllegalStateException("Something went wrong while using a tool."))
    }

    private suspend fun runTool(call: ToolRequest, cloud: Boolean, listener: ResponseListener): ToolResult {
        val tool = tools.get(call.name)
            ?: return ToolResult(false, "Unknown tool: " + call.name)
        if (tool.readsPrivateData && cloud && !settings.shareMemoryWithCloud) {
            return ToolResult(false, "Not allowed: this would send private data to the cloud model.")
        }
        listener.onStatus("Using tool: " + tool.name)
        return toolExecutor.execute(tool, call.args)
    }

    private class Generation(val result: Result<String>, val cloud: Boolean)

    private suspend fun generate(
        conversation: List<ChatMessage>,
        memoryLines: List<String>,
        toolsSection: String?,
        listener: ResponseListener
    ): Generation {
        // Memories always go to the local model. They go to Groq only if the user allowed it.
        val localMessages = prompts.build(conversation, memoryLines, toolsSection)
        val cloudMessages = prompts.build(
            conversation,
            if (settings.shareMemoryWithCloud) memoryLines else emptyList(),
            toolsSection
        )
        return when (settings.engineMode) {
            EngineMode.LOCAL -> Generation(runLocal(localMessages, listener), false)
            EngineMode.CLOUD -> Generation(runCloud(cloudMessages, listener), true)
            EngineMode.AUTO -> {
                if (localEngine.isReady()) {
                    val local = runLocal(localMessages, listener)
                    val failure = local.exceptionOrNull()
                    if (failure == null || failure is StoppedException || !keyStore.has(GROQ)) {
                        Generation(local, false)
                    } else {
                        Generation(runCloud(cloudMessages, listener), true)
                    }
                } else {
                    Generation(runCloud(cloudMessages, listener), true)
                }
            }
        }
    }

    private suspend fun runLocal(messages: List<ChatMessage>, listener: ResponseListener): Result<String> {
        listener.onEngineChosen(LOCAL_NAME)
        return localEngine.chat(messages) { listener.onToken(it) }
    }

    private suspend fun runCloud(messages: List<ChatMessage>, listener: ResponseListener): Result<String> {
        listener.onEngineChosen(CLOUD_NAME)
        val provider = registry.chatProvider(GROQ)
            ?: return Result.failure(IllegalStateException("No chat service is available."))
        return provider.chat(messages)
    }

    /**
     * Holds back the first few characters of a reply. If it starts with "TOOL:" it is a tool
     * request and is not shown to the user; otherwise it is passed on as normal text.
     */
    private class ToolGate(private val delegate: ResponseListener) : ResponseListener {
        private val buffer = StringBuilder()
        private var decided = false
        private var isTool = false

        override fun onEngineChosen(name: String) = delegate.onEngineChosen(name)

        override fun onStatus(text: String) = delegate.onStatus(text)

        override fun onToken(text: String) {
            if (decided) {
                if (!isTool) delegate.onToken(text)
                return
            }
            buffer.append(text)
            val head = buffer.toString().trimStart()
            if (head.length >= PREFIX.length || !PREFIX.startsWith(head)) {
                decided = true
                isTool = head.startsWith(PREFIX)
                if (!isTool) delegate.onToken(buffer.toString())
            }
        }

        /** Reply ended before we could decide (very short reply): show it. */
        fun finish() {
            if (!decided && buffer.isNotEmpty()) {
                decided = true
                delegate.onToken(buffer.toString())
            }
        }

        companion object {
            private const val PREFIX = "TOOL:"
        }
    }

    companion object {
        const val LOCAL_NAME = "Local"
        const val CLOUD_NAME = "Groq"
        const val MEMORY_NAME = "Memory"
        private const val GROQ = "groq"
        private const val MAX_TOOL_STEPS = 3
    }
}
