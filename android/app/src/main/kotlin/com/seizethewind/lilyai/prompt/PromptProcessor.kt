package com.seizethewind.lilyai.prompt

import com.seizethewind.lilyai.api.ChatMessage

/** Turns the visible conversation, plus any memories, into the messages sent to a model. */
class PromptProcessor {

    fun build(history: List<ChatMessage>, memories: List<String> = emptyList()): List<ChatMessage> {
        val recent = history.takeLast(MAX_HISTORY)
        val system = if (memories.isEmpty()) {
            SYSTEM_PROMPT
        } else {
            SYSTEM_PROMPT + "\n\nThings the user asked you to remember (use them only when relevant):\n" +
                memories.joinToString("\n") { "- $it" }
        }
        return listOf(ChatMessage("system", system)) + recent
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val SYSTEM_PROMPT =
            "You are LilyAi, a helpful assistant running on the user's Android phone. Be concise and clear."
    }
}
