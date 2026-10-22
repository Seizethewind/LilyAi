package com.seizethewind.lilyai.core

import com.seizethewind.lilyai.api.ApiRegistry
import com.seizethewind.lilyai.api.ChatMessage
import com.seizethewind.lilyai.prompt.PromptProcessor
import com.seizethewind.lilyai.settings.SettingsManager

/**
 * The assistant's brain. For now it only chats; tools, planning and the local model
 * are added in later phases.
 */
class Agent(
    private val registry: ApiRegistry,
    private val settings: SettingsManager,
    private val prompts: PromptProcessor = PromptProcessor()
) {

    suspend fun respond(history: List<ChatMessage>): Result<String> {
        if (settings.localOnly) {
            return Result.failure(
                IllegalStateException("Local only is on, but the local model is not installed yet.")
            )
        }
        val provider = registry.chatProvider("groq")
            ?: return Result.failure(IllegalStateException("No chat service is available."))
        return provider.chat(prompts.build(history))
    }
}
