package ai.lily.app.memory

import java.util.UUID

/**
 * Context levels, saved prompts, insights and chats, stored in the knowledge database next to [KnowledgeRepository].
 *
 * - A level belongs to the link between a notebook and a source. No row means FULL.
 * - Built-in prompts live in code ([BuiltinPrompts]); only the person's own are stored.
 * - An insight is one per source and prompt; making it again replaces it.
 * - A question and its reply are written together or not at all, so a failed question leaves no trace in the chat.
 *
 * Methods that look something up return null or false when it is not there. Bad input throws [IllegalArgumentException].
 * The proven facts database is never touched.
 */
class ContextRepository(
    private val db: SqlDatabase,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val schema = SchemaGate(KnowledgeSchema.migrator(db))

    private suspend fun ensureReady() = schema.ensure()

    // ---- Context levels ----

    /** The level of a linked source, or null if the source is not linked to the notebook. */
    suspend fun levelOf(notebookId: String, sourceId: String): ContextLevel? {
        ensureReady()
        if (!isLinked(notebookId, sourceId)) return null
        return levelFrom(db.execute(SELECT_LEVEL, listOf(notebookId, sourceId)).rows.firstOrNull()?.firstOrNull())
    }

    /** False if the source is not linked to the notebook. Setting FULL removes the stored row. */
    suspend fun setLevel(notebookId: String, sourceId: String, level: ContextLevel): Boolean {
        ensureReady()
        if (level == ContextLevel.FULL) {
            db.execute(CLEAR_LEVEL, listOf(notebookId, sourceId))
            return isLinked(notebookId, sourceId)
        }
        val args = listOf(notebookId, sourceId, level.name, now(), notebookId, sourceId)
        return db.execute(SET_LEVEL, args).affectedRows > 0
    }

    /** The sources of a notebook that are not FULL, by source id. A source missing from the map is FULL. */
    suspend fun levels(notebookId: String): Map<String, ContextLevel> {
        ensureReady()
        return db.execute(LIST_LEVELS, listOf(notebookId)).rows.associate { it[0].orEmpty() to levelFrom(it[1]) }
    }

    // ---- Saved prompts ----

    /** Built-in prompts first, then the person's own by title. */
    suspend fun listPrompts(): List<SavedPrompt> {
        ensureReady()
        return BuiltinPrompts.all + db.execute(LIST_PROMPTS).rows.map(::promptOf)
    }

    suspend fun getPrompt(id: String): SavedPrompt? {
        BuiltinPrompts.all.firstOrNull { it.id == id }?.let { return it }
        ensureReady()
        return db.execute(SELECT_PROMPT, listOf(id)).rows.firstOrNull()?.let(::promptOf)
    }

    suspend fun savePrompt(title: String, prompt: String): SavedPrompt {
        val (name, text) = cleanPrompt(title, prompt)
        ensureReady()
        requireTitleFree(name, exceptId = null)
        val id = newId()
        val t = now()
        db.execute(INSERT_PROMPT, listOf(id, name, text, t, t))
        return SavedPrompt(id, name, text, builtin = false, createdAt = t, updatedAt = t)
    }

    suspend fun updatePrompt(id: String, title: String, prompt: String): Boolean {
        require(BuiltinPrompts.all.none { it.id == id }) { BUILTIN_LOCKED }
        val (name, text) = cleanPrompt(title, prompt)
        ensureReady()
        requireTitleFree(name, exceptId = id)
        return db.execute(UPDATE_PROMPT, listOf(name, text, now(), id)).affectedRows > 0
    }

    /** Deletes the prompt and the insights made with it. */
    suspend fun deletePrompt(id: String): Boolean {
        require(BuiltinPrompts.all.none { it.id == id }) { BUILTIN_LOCKED }
        ensureReady()
        val results = db.transaction(listOf(Stmt(DELETE_PROMPT_INSIGHTS, listOf(id)), Stmt(DELETE_PROMPT, listOf(id))))
        return results.last().affectedRows > 0
    }

    // ---- Insights ----

    /** Saves the insight, replacing the one for the same source and prompt. [sourceHash] is the hash of the text it was made from. */
    suspend fun saveInsight(
        sourceId: String,
        prompt: SavedPrompt,
        sourceHash: String,
        body: String,
        truncated: Boolean,
        routedVia: String?,
    ): Insight {
        require(body.isNotBlank()) { "An insight needs some text." }
        ensureReady()
        val t = now()
        val args = listOf(
            newId(), sourceId, prompt.id, prompt.title, prompt.contentHash, sourceHash, body,
            if (truncated) 1 else 0, routedVia.orEmpty(), t, sourceId,
        )
        val results = db.transaction(listOf(Stmt(UPSERT_INSIGHT, args), Stmt(SELECT_INSIGHT_ID, listOf(sourceId, prompt.id))))
        val id = results[1].rows.firstOrNull()?.firstOrNull() ?: throw IllegalArgumentException("That source does not exist.")
        return Insight(id, sourceId, prompt.id, prompt.title, prompt.contentHash, sourceHash, body, truncated, routedVia?.ifEmpty { null }, t, stale = false)
    }

    suspend fun listInsights(sourceId: String): List<Insight> = insightsFor(listOf(sourceId))[sourceId].orEmpty()

    /** The insights of the given sources, by source id. Sources without any are absent from the map. */
    suspend fun insightsFor(sourceIds: Collection<String>): Map<String, List<Insight>> {
        ensureReady()
        val ids = sourceIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val currentPrompts = listPrompts().associate { it.id to it.contentHash }
        val out = LinkedHashMap<String, MutableList<Insight>>()
        for (batch in ids.chunked(IN_BATCH)) {
            val marks = batch.joinToString(", ") { "?" }
            val sql = "SELECT $INSIGHT_COLS FROM source_insights i JOIN sources s ON s.id = i.source_id WHERE i.source_id IN ($marks) ORDER BY i.source_id, i.prompt_title, i.id"
            db.execute(sql, batch).rows.forEach { r ->
                val insight = insightOf(r, currentPrompts)
                out.getOrPut(insight.sourceId) { ArrayList() } += insight
            }
        }
        return out
    }

    suspend fun deleteInsight(id: String): Boolean {
        ensureReady()
        return db.execute(DELETE_INSIGHT, listOf(id)).affectedRows > 0
    }

    // ---- Chats ----

    suspend fun createSession(notebookId: String, title: String = ""): ChatSession {
        ensureReady()
        require(db.execute(NOTEBOOK_EXISTS, listOf(notebookId)).rows.isNotEmpty()) { "That notebook does not exist." }
        val name = title.trim().ifEmpty { DEFAULT_TITLE }.take(TITLE_LENGTH)
        val id = newId()
        val t = now()
        db.execute(INSERT_SESSION, listOf(id, notebookId, name, t, t))
        return ChatSession(id, notebookId, name, t, t)
    }

    suspend fun getSession(id: String): ChatSession? {
        ensureReady()
        return db.execute(SELECT_SESSION, listOf(id)).rows.firstOrNull()?.let(::sessionOf)
    }

    /** Most recently used first. */
    suspend fun listSessions(notebookId: String): List<ChatSession> {
        ensureReady()
        return db.execute(LIST_SESSIONS, listOf(notebookId)).rows.map(::sessionOf)
    }

    suspend fun renameSession(id: String, title: String): Boolean {
        val name = title.trim()
        require(name.isNotEmpty()) { "A chat needs a title." }
        ensureReady()
        return db.execute(RENAME_SESSION, listOf(name.take(TITLE_LENGTH), id)).affectedRows > 0
    }

    suspend fun deleteSession(id: String): Boolean {
        ensureReady()
        val results = db.transaction(listOf(Stmt(DELETE_SESSION_MESSAGES, listOf(id)), Stmt(DELETE_SESSION, listOf(id))))
        return results.last().affectedRows > 0
    }

    suspend fun listMessages(sessionId: String): List<ChatMessage> {
        ensureReady()
        return db.execute(LIST_MESSAGES, listOf(sessionId)).rows.map(::messageOf)
    }

    /** The last [limit] messages, oldest first. */
    suspend fun recentMessages(sessionId: String, limit: Int): List<ChatMessage> {
        ensureReady()
        return db.execute(RECENT_MESSAGES, listOf(sessionId, limit.coerceAtLeast(1))).rows.map(::messageOf)
    }

    suspend fun getMessage(id: String): ChatMessage? {
        ensureReady()
        return db.execute(SELECT_MESSAGE, listOf(id)).rows.firstOrNull()?.let(::messageOf)
    }

    suspend fun messageAt(sessionId: String, ordinal: Int): ChatMessage? {
        ensureReady()
        return db.execute(SELECT_MESSAGE_AT, listOf(sessionId, ordinal)).rows.firstOrNull()?.let(::messageOf)
    }

    /**
     * Adds a question and its reply to a chat in one transaction. The first exchange also gives an untitled chat its title.
     * Returns the stored question and reply.
     */
    suspend fun appendExchange(
        sessionId: String,
        question: String,
        answer: String,
        citations: List<AskCitation>,
        notes: List<String>,
    ): Pair<ChatMessage, ChatMessage> {
        require(question.isNotBlank()) { "The question is empty." }
        require(answer.isNotBlank()) { "The reply is empty." }
        ensureReady()
        requireNotNull(getSession(sessionId)) { "That chat does not exist." }
        val t = now()
        val questionId = newId()
        val replyId = newId()
        val title = question.trim().replace(Regex("\\s+"), " ").take(TITLE_LENGTH)
        db.transaction(
            listOf(
                Stmt(APPEND_MESSAGE, listOf(questionId, sessionId, ChatRole.USER.name, question, "[]", "[]", t, sessionId)),
                Stmt(
                    APPEND_MESSAGE,
                    listOf(replyId, sessionId, ChatRole.ASSISTANT.name, answer, ChatCodec.encodeCitations(citations), ChatCodec.encodeNotes(notes), t, sessionId),
                ),
                Stmt(TOUCH_SESSION, listOf(t, DEFAULT_TITLE, title, sessionId)),
            ),
        )
        val q = getMessage(questionId) ?: error("The question was saved but could not be read back.")
        val r = getMessage(replyId) ?: error("The reply was saved but could not be read back.")
        return q to r
    }

    // ---- Helpers ----

    private suspend fun isLinked(notebookId: String, sourceId: String) =
        db.execute(IS_LINKED, listOf(notebookId, sourceId)).rows.isNotEmpty()

    private suspend fun requireTitleFree(title: String, exceptId: String?) {
        val taken = listPrompts().any { it.id != exceptId && it.title.equals(title, ignoreCase = true) }
        require(!taken) { "There is already a prompt called \"$title\"." }
    }

    private fun cleanPrompt(title: String, prompt: String): Pair<String, String> {
        val name = title.trim()
        val text = prompt.trim()
        require(name.isNotEmpty()) { "A prompt needs a title." }
        require(name.length <= MAX_PROMPT_TITLE) { "A prompt title can be at most $MAX_PROMPT_TITLE characters." }
        require(text.isNotEmpty()) { "A prompt needs some text." }
        require(text.length <= MAX_PROMPT_TEXT) { "A prompt can be at most $MAX_PROMPT_TEXT characters." }
        return name to text
    }

    private fun levelFrom(text: String?) = when (text) {
        "OFF" -> ContextLevel.OFF
        "INSIGHTS" -> ContextLevel.INSIGHTS
        else -> ContextLevel.FULL
    }

    private fun promptOf(r: List<String?>) = SavedPrompt(
        id = r[0].orEmpty(),
        title = r[1].orEmpty(),
        prompt = r[2].orEmpty(),
        builtin = false,
        createdAt = r[3]?.toLongOrNull() ?: 0L,
        updatedAt = r[4]?.toLongOrNull() ?: 0L,
    )

    private fun insightOf(r: List<String?>, currentPrompts: Map<String, String>): Insight {
        val promptId = r[2].orEmpty()
        val promptHash = r[4].orEmpty()
        val sourceHash = r[5].orEmpty()
        val sourceChanged = sourceHash != r[10].orEmpty()
        val promptChanged = currentPrompts[promptId]?.let { it != promptHash } ?: false
        return Insight(
            id = r[0].orEmpty(),
            sourceId = r[1].orEmpty(),
            promptId = promptId,
            promptTitle = r[3].orEmpty(),
            promptHash = promptHash,
            sourceHash = sourceHash,
            body = r[6].orEmpty(),
            truncated = r[7] == "1",
            routedVia = r[8]?.ifEmpty { null },
            createdAt = r[9]?.toLongOrNull() ?: 0L,
            stale = sourceChanged || promptChanged,
        )
    }

    private fun sessionOf(r: List<String?>) = ChatSession(
        id = r[0].orEmpty(),
        notebookId = r[1].orEmpty(),
        title = r[2].orEmpty(),
        createdAt = r[3]?.toLongOrNull() ?: 0L,
        updatedAt = r[4]?.toLongOrNull() ?: 0L,
    )

    private fun messageOf(r: List<String?>) = ChatMessage(
        id = r[0].orEmpty(),
        sessionId = r[1].orEmpty(),
        ordinal = r[2]?.toIntOrNull() ?: 0,
        role = if (r[3] == ChatRole.ASSISTANT.name) ChatRole.ASSISTANT else ChatRole.USER,
        body = r[4].orEmpty(),
        citations = ChatCodec.decodeCitations(r[5].orEmpty()),
        notes = ChatCodec.decodeNotes(r[6].orEmpty()),
        createdAt = r[7]?.toLongOrNull() ?: 0L,
    )

    companion object {
        const val DEFAULT_TITLE = "New chat"
        const val MAX_PROMPT_TITLE = 80
        const val MAX_PROMPT_TEXT = 4000
        private const val TITLE_LENGTH = 60
        private const val IN_BATCH = 100
        private const val BUILTIN_LOCKED = "Built-in prompts cannot be changed. Save a copy under another title instead."

        private const val NOTEBOOK_EXISTS = "SELECT 1 FROM notebooks WHERE id = ?"
        private const val IS_LINKED = "SELECT 1 FROM notebook_sources WHERE notebook_id = ? AND source_id = ?"
        private const val SELECT_LEVEL = "SELECT level FROM source_context WHERE notebook_id = ? AND source_id = ?"
        private const val CLEAR_LEVEL = "DELETE FROM source_context WHERE notebook_id = ? AND source_id = ?"
        private const val SET_LEVEL = "INSERT INTO source_context (notebook_id, source_id, level, updated_at) SELECT ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM notebook_sources WHERE notebook_id = ? AND source_id = ?) ON CONFLICT (notebook_id, source_id) DO UPDATE SET level = excluded.level, updated_at = excluded.updated_at"
        private const val LIST_LEVELS = "SELECT source_id, level FROM source_context WHERE notebook_id = ?"

        private const val LIST_PROMPTS = "SELECT id, title, prompt, created_at, updated_at FROM saved_prompts ORDER BY title, id"
        private const val SELECT_PROMPT = "SELECT id, title, prompt, created_at, updated_at FROM saved_prompts WHERE id = ?"
        private const val INSERT_PROMPT = "INSERT INTO saved_prompts (id, title, prompt, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
        private const val UPDATE_PROMPT = "UPDATE saved_prompts SET title = ?, prompt = ?, updated_at = ? WHERE id = ?"
        private const val DELETE_PROMPT_INSIGHTS = "DELETE FROM source_insights WHERE prompt_id = ?"
        private const val DELETE_PROMPT = "DELETE FROM saved_prompts WHERE id = ?"

        private const val INSIGHT_COLS = "i.id, i.source_id, i.prompt_id, i.prompt_title, i.prompt_hash, i.source_hash, i.body, i.truncated, i.routed_via, i.created_at, s.content_hash"
        private const val UPSERT_INSIGHT = "INSERT INTO source_insights (id, source_id, prompt_id, prompt_title, prompt_hash, source_hash, body, truncated, routed_via, created_at) SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM sources WHERE id = ?) ON CONFLICT (source_id, prompt_id) DO UPDATE SET prompt_title = excluded.prompt_title, prompt_hash = excluded.prompt_hash, source_hash = excluded.source_hash, body = excluded.body, truncated = excluded.truncated, routed_via = excluded.routed_via, created_at = excluded.created_at"
        private const val SELECT_INSIGHT_ID = "SELECT id FROM source_insights WHERE source_id = ? AND prompt_id = ?"
        private const val DELETE_INSIGHT = "DELETE FROM source_insights WHERE id = ?"

        private const val SESSION_COLS = "id, notebook_id, title, created_at, updated_at"
        private const val INSERT_SESSION = "INSERT INTO chat_sessions (id, notebook_id, title, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
        private const val SELECT_SESSION = "SELECT $SESSION_COLS FROM chat_sessions WHERE id = ?"
        private const val LIST_SESSIONS = "SELECT $SESSION_COLS FROM chat_sessions WHERE notebook_id = ? ORDER BY updated_at DESC, id"
        private const val RENAME_SESSION = "UPDATE chat_sessions SET title = ? WHERE id = ?"
        private const val DELETE_SESSION_MESSAGES = "DELETE FROM chat_messages WHERE session_id = ?"
        private const val DELETE_SESSION = "DELETE FROM chat_sessions WHERE id = ?"
        private const val TOUCH_SESSION = "UPDATE chat_sessions SET updated_at = ?, title = CASE WHEN title = ? THEN ? ELSE title END WHERE id = ?"

        private const val MESSAGE_COLS = "id, session_id, ordinal, role, body, citations, notes, created_at"
        private const val LIST_MESSAGES = "SELECT $MESSAGE_COLS FROM chat_messages WHERE session_id = ? ORDER BY ordinal"
        private const val RECENT_MESSAGES = "SELECT $MESSAGE_COLS FROM (SELECT $MESSAGE_COLS FROM chat_messages WHERE session_id = ? ORDER BY ordinal DESC LIMIT ?) ORDER BY ordinal"
        private const val SELECT_MESSAGE = "SELECT $MESSAGE_COLS FROM chat_messages WHERE id = ?"
        private const val SELECT_MESSAGE_AT = "SELECT $MESSAGE_COLS FROM chat_messages WHERE session_id = ? AND ordinal = ?"
        private const val APPEND_MESSAGE = "INSERT INTO chat_messages (id, session_id, ordinal, role, body, citations, notes, created_at) SELECT ?, ?, COALESCE(MAX(ordinal), 0) + 1, ?, ?, ?, ?, ? FROM chat_messages WHERE session_id = ?"
    }
}
