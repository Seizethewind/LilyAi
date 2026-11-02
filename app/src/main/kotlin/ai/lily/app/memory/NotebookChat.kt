package ai.lily.app.memory

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import kotlin.coroutines.cancellation.CancellationException

/** [askedAs] is the standalone question the message was turned into, or null when it was searched as written. */
data class ChatTurn(val question: ChatMessage, val reply: ChatMessage, val answer: AskAnswer, val askedAs: String?)

/**
 * A conversation with one notebook. Each message is answered by [KnowledgeAsker] from the notebook's sources, with citations.
 *
 * Follow-ups: when the chat already has messages, the new one is first rewritten into a standalone question using the last few
 * messages ([FollowUpRewriter]). If that fails or is unusable, the message is searched as written and a note says so. The answer
 * itself is still made only from the retrieved excerpts, so an instruction about the previous answer ("shorter", "translate it")
 * is not something this can do.
 *
 * A question and its reply are stored together after the answer succeeds; if answering fails, the error reaches the caller and the
 * chat is unchanged.
 */
class NotebookChat(
    private val gateway: ModelGateway,
    private val asker: KnowledgeAsker,
    private val context: ContextRepository,
    private val model: String? = null,
) {
    suspend fun send(sessionId: String, message: String): ChatTurn {
        val text = message.trim()
        require(text.isNotEmpty()) { "Write a message." }
        require(text.length <= MAX_MESSAGE_CHARS) { "That message is too long (over $MAX_MESSAGE_CHARS characters)." }
        val session = context.getSession(sessionId) ?: throw IllegalArgumentException("That chat does not exist.")
        val history = context.recentMessages(sessionId, FollowUpRewriter.HISTORY_MESSAGES)

        val notes = ArrayList<String>()
        var asked = text
        var askedAs: String? = null
        if (history.isNotEmpty()) {
            try {
                val reply = gateway.complete(ModelRequest(FollowUpRewriter.prompt(history, text), FollowUpRewriter.SYSTEM, model))
                val rewritten = FollowUpRewriter.parse(reply.text)
                if (rewritten == null) {
                    notes += "Could not turn your message into a standalone question; it was searched as written."
                } else if (rewritten != text) {
                    asked = rewritten
                    askedAs = rewritten
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "Reading your message in context failed (${e.message ?: e.javaClass.simpleName}); it was searched as written."
            }
        }

        val answer = asker.ask(asked, session.notebookId)
        val stored = ArrayList<String>()
        askedAs?.let { stored += "Understood as: $it" }
        stored += notes
        stored += answer.notes
        val (question, reply) = context.appendExchange(sessionId, text, answer.text, answer.citations, stored.distinct())
        return ChatTurn(question, reply, answer, askedAs)
    }

    /**
     * Saves a reply from the chat as an AI note in the chat's notebook, with the same rules as [KnowledgeAsker.saveAsNote]: it must
     * cite source text, and it cites only the sources whose text it drew on. A source deleted since makes this fail.
     */
    suspend fun saveReplyAsNote(messageId: String, title: String? = null): Note {
        val reply = context.getMessage(messageId) ?: throw IllegalArgumentException("That message does not exist.")
        require(reply.role == ChatRole.ASSISTANT) { "Only a reply can be saved as a note." }
        val session = context.getSession(reply.sessionId) ?: throw IllegalArgumentException("That chat does not exist.")
        val question = context.messageAt(reply.sessionId, reply.ordinal - 1)?.takeIf { it.role == ChatRole.USER }?.body ?: "Chat answer"
        val answer = AskAnswer(question, reply.body, reply.citations, emptyList(), 0, reply.notes, null)
        return asker.saveAsNote(session.notebookId, answer, title)
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 4_000
    }
}
