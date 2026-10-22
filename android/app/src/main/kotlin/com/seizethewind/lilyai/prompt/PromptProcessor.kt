package com.seizethewind.lilyai.prompt

import com.seizethewind.lilyai.api.ChatMessage

/** Turns the visible conversation into the messages sent to a model. */
class PromptProcessor {

    fun build(history: List<ChatMessage>): List<ChatMessage> {
        val recent = history.takeLast(MAX_HISTORY)
        return listOf(ChatMessage("system", SYSTEM_PROMPT)) + recent
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val SYSTEM_PROMPT =
            "You are LilyAi, a helpful assistant running on the user's Android phone. Be concise and clear."
    }
}
