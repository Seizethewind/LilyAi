package com.seizethewind.lilyai.core

import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.api.ApiRegistry
import com.seizethewind.lilyai.api.ChatMessage
import com.seizethewind.lilyai.inference.LlamaEngine
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
 * The assistant's brain. For now it chats and picks the engine; tools, planning and
 * smarter routing are added in later phases.
 */
class Agent(
    private val registry: ApiRegistry,
    private val settings: SettingsManager,
    private val localEngine: LlamaEngine,
    private val keyStore: ApiKeyStore,
    private val prompts: PromptProcessor = PromptProcessor()
) {

    suspend fun respond(history: List<ChatMessage>, listener: ResponseListener): Result<String> {
        val messages = prompts.build(history)
        return when (settings.engineMode) {
            EngineMode.LOCAL -> runLocal(messages, listener)
            EngineMode.CLOUD -> runCloud(messages, listener)
            EngineMode.AUTO -> {
                if (localEngine.isReady()) {
                    val local = runLocal(messages, listener)
                    val failure = local.exceptionOrNull()
                    if (failure == null || failure is StoppedException || !keyStore.has(GROQ)) {
                        local
                    } else {
                        runCloud(messages, listener)
                    }
                } else {
                    runCloud(messages, listener)
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
        private const val GROQ = "groq"
    }
}
