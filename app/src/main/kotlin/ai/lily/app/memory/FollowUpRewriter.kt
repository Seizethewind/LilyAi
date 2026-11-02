package ai.lily.app.memory

/**
 * Turns a follow-up such as "and how long?" into a question that makes sense on its own, using the last few messages of the chat,
 * so that searching the library has something to search for. The model's reply is read strictly: anything that does not look like
 * a single short question is refused, and the caller searches the message as written.
 */
object FollowUpRewriter {
    const val HISTORY_MESSAGES = 6
    private const val CLIP = 600
    private const val MAX_QUESTION = 500
    private val CLOSING_TAG = Regex("</history", RegexOption.IGNORE_CASE)

    const val SYSTEM = "You rewrite the latest message of a conversation about a personal notebook into one standalone question that " +
        "makes sense without the conversation. Replace pronouns and references (\"it\", \"that one\", \"the second\") with what they " +
        "refer to. Do not answer the question and do not add facts. If the message already stands on its own, repeat it unchanged. " +
        "Reply with only the question, on one line."

    fun prompt(history: List<ChatMessage>, message: String): String = buildString {
        append("<history>\n")
        history.takeLast(HISTORY_MESSAGES).forEach { m ->
            val who = if (m.role == ChatRole.USER) "Person" else "Assistant"
            val text = clipText(m.body.trim(), CLIP).replace(CLOSING_TAG) { "<\\/history" }
            append(who).append(": ").append(text).append('\n')
        }
        append("</history>\n\nLatest message: ").append(message).append("\n\nStandalone question:")
    }

    /** The question, or null if the reply is empty, too long, or spans several lines (likely an answer, not a rewrite). */
    fun parse(reply: String): String? {
        var s = reply.trim().removePrefix("Standalone question:").trim()
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1).trim()
        if (s.isEmpty() || s.length > MAX_QUESTION || s.contains('\n')) return null
        return s
    }
}
