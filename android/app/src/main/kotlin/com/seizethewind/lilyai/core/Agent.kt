package com.seizethewind.lilyai.core

import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.api.ApiRegistry
import com.seizethewind.lilyai.api.ChatMessage
import com.seizethewind.lilyai.inference.LlamaEngine
import com.seizethewind.lilyai.memory.MemoryManager
import com.seizethewind.lilyai.prompt.PromptProcessor
import com.seizethewind.lilyai.settings.EngineMode
import com.seizethewind.lilyai.settings.SettingsManager

/** Thrown/returned when the user pressed Stop. Not shown as an error. */
class StoppedException : Exception("Stopped.")

/** Lets the UI follow along while an answer is being produced. */
interface ResponseListener {
    fun onEngineChosen(name: String) {}
    fun onToken(text: String) {}
}

/**
 * The assistant's brain. For now it chats, remembers things on request and picks the engine;
 * tools, planning and smarter routing are added in later phases.
 */
class Agent(
    private val registry: ApiRegistry,
    private val settings: SettingsManager,
    private val localEngine: LlamaEngine,
    private val keyStore: ApiKeyStore,
    private val memory: MemoryManager,
    private val prompts: PromptProcessor = PromptProcessor()
) {

    suspend fun respond(history: List<ChatMessage>, listener: ResponseListener): Result<String> {
        val lastUser = history.lastOrNull { it.role == "user" }?.content.orEmpty()

        // "remember ..." is handled directly and never sent to any model.
        memory.tryHandleCommand(lastUser)?.let { reply ->
            listener.onEngineChosen(MEMORY_NAME)
            return Result.success(reply)
        }

        // Memories always go to the local model. They go to Groq only if the user allowed it.
        val memoryLines = memory.contextLines(lastUser)
        val localMessages = prompts.build(history, memoryLines)
        val cloudMessages = prompts.build(
            history,
            if (settings.shareMemoryWithCloud) memoryLines else emptyList()
        )

        return when (settings.engineMode) {
            EngineMode.LOCAL -> runLocal(localMessages, listener)
            EngineMode.CLOUD -> runCloud(cloudMessages, listener)
            EngineMode.AUTO -> {
                if (localEngine.isReady()) {
                    val local = runLocal(localMessages, listener)
                    val failure = local.exceptionOrNull()
                    if (failure == null || failure is StoppedException || !keyStore.has(GROQ)) {
                        local
                    } else {
                        runCloud(cloudMessages, listener)
                    }
                } else {
                    runCloud(cloudMessages, listener)
                }
            }
        }
    }

    /** Stops local generation. Cloud requests are cancelled by the caller. */
    fun stop() = localEngine.cancel()

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

    companion object {
        const val LOCAL_NAME = "Local"
        const val CLOUD_NAME = "Groq"
        const val MEMORY_NAME = "Memory"
        private const val GROQ = "groq"
    }
}
