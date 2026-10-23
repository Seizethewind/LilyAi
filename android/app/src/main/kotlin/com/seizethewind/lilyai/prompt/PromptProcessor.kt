package com.seizethewind.lilyai.prompt

import com.seizethewind.lilyai.api.ChatMessage

/** Turns the visible conversation, memories and tool instructions into the messages sent to a model. */
class PromptProcessor {

    fun build(
        history: List<ChatMessage>,
        memories: List<String> = emptyList(),
        toolsSection: String? = null
    ): List<ChatMessage> {
        val recent = history.takeLast(MAX_HISTORY)
        val system = StringBuilder(SYSTEM_PROMPT)
        if (memories.isNotEmpty()) {
            system.append("\n\nThings the user asked you to remember (use them only when relevant):\n")
            system.append(memories.joinToString("\n") { "- $it" })
        }
        if (toolsSection != null) {
            system.append("\n\n").append(toolsSection)
        }
        return listOf(ChatMessage("system", system.toString())) + recent
    }

    companion object {
        private const val MAX_HISTORY = 20
        private const val SYSTEM_PROMPT =
            "You are LilyAi, a helpful assistant running on the user's Android phone. Be concise and clear."
    }
}
