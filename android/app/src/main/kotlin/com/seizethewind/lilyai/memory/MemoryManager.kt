package com.seizethewind.lilyai.memory

/**
 * Memory is only ever created when the user asks: by saying "remember ..." in chat or by
 * adding a note on the Memories screen. Nothing is inferred or saved silently.
 */
class MemoryManager(private val longTerm: LongTermMemory) {

    /** Called after every change so cloud sync can run. */
    var onChanged: (() -> Unit)? = null

    private val rememberCommand = Regex(
        "^\\s*(?:please\\s+)?remember(?:\\s+that)?\\s*[:,]?\\s+(.+)$",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun all(): List<MemoryItem> = longTerm.all()

    fun remember(text: String): MemoryItem {
        val item = longTerm.add(text)
        onChanged?.invoke()
        return item
    }

    fun forget(id: String) {
        longTerm.delete(id)
        onChanged?.invoke()
    }

    /**
     * Handles "remember ..." messages directly, without asking a model. Returns the reply to show,
     * or null if the message is not a remember command.
     */
    fun tryHandleCommand(userText: String): String? {
        val match = rememberCommand.find(userText) ?: return null
        val text = match.groupValues[1].trim()
        if (text.length < 3) return null
        if (text.length > MAX_NOTE_CHARS) {
            return "That is too long to keep as one note. Please shorten it and try again."
        }
        remember(text)
        return "Saved to memory: $text"
    }

    /**
     * Notes to add to the prompt. With a few memories all of them go in; with more, only the best
     * keyword matches for this message. Kept short so the small local model keeps room to answer.
     */
    fun contextLines(query: String): List<String> {
        val all = longTerm.all()
        val chosen = if (all.size <= SMALL_SET) all else longTerm.search(query, SEARCH_LIMIT)
        var budget = CONTEXT_CHARS
        val lines = mutableListOf<String>()
        for (item in chosen) {
            val text = item.text.take(300)
            if (text.length > budget) break
            lines.add(text)
            budget -= text.length
        }
        return lines
    }

    companion object {
        private const val MAX_NOTE_CHARS = 500
        private const val SMALL_SET = 8
        private const val SEARCH_LIMIT = 5
        private const val CONTEXT_CHARS = 1000
    }
}
