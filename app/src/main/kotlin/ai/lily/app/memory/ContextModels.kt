package ai.lily.app.memory

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * How much of a source one notebook shows the model.
 * - [OFF]: nothing. The source is not searched and none of its insights are sent.
 * - [INSIGHTS]: only its saved insights (AI-written analyses). Its text is not searched.
 * - [FULL]: its text is searched, as before. This is the default.
 *
 * The level belongs to the link between a notebook and a source, so the same source can be FULL in one notebook and OFF in another.
 */
enum class ContextLevel { OFF, INSIGHTS, FULL }

/** A prompt that can be run over a source to make an insight. Built-in ones live in code and cannot be changed. */
data class SavedPrompt(
    val id: String,
    val title: String,
    val prompt: String,
    val builtin: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** What an insight remembers about the prompt it was made with, so a changed prompt makes old insights stale. */
    val contentHash: String get() = KnowledgeText.hash(prompt)
}

object BuiltinPrompts {
    val SUMMARY = builtin(
        "builtin:summary", "Summary",
        "Summarise this source in 5 to 8 sentences: what it is, its main points and any conclusions it reaches. Use only what the source says.",
    )
    val KEY_CLAIMS = builtin(
        "builtin:key-claims", "Key claims",
        "List the main factual claims this source makes, one per line starting with \"- \". Stay close to the source's own wording. " +
            "Mark a claim (the source's opinion) or (uncertain) when the source presents it that way. Do not add claims the source does not make.",
    )
    val OPEN_QUESTIONS = builtin(
        "builtin:open-questions", "Open questions",
        "List up to 8 questions this source raises but does not answer, one per line starting with \"- \".",
    )
    val KEY_TERMS = builtin(
        "builtin:key-terms", "Key terms",
        "List the important terms and names in this source, one per line as \"term: short definition\", defined only as the source uses them.",
    )

    val all: List<SavedPrompt> = listOf(SUMMARY, KEY_CLAIMS, OPEN_QUESTIONS, KEY_TERMS)

    private fun builtin(id: String, title: String, prompt: String) = SavedPrompt(id, title, prompt, builtin = true, createdAt = 0L, updatedAt = 0L)
}

/**
 * An AI-written analysis of one source. It is never evidence on its own. [stale] is true when the source text or the prompt has
 * changed since it was made; a stale insight is kept (so it can be shown) but is not sent to the model.
 * [truncated] means the source was longer than what the model was shown.
 */
data class Insight(
    val id: String,
    val sourceId: String,
    val promptId: String,
    val promptTitle: String,
    val promptHash: String,
    val sourceHash: String,
    val body: String,
    val truncated: Boolean,
    val routedVia: String?,
    val createdAt: Long,
    val stale: Boolean,
)

enum class ChatRole { USER, ASSISTANT }

data class ChatSession(val id: String, val notebookId: String, val title: String, val createdAt: Long, val updatedAt: Long)

/** [ordinal] counts from 1 within the chat. Only replies carry [citations]. [notes] are things worth telling the reader. */
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val ordinal: Int,
    val role: ChatRole,
    val body: String,
    val citations: List<AskCitation>,
    val notes: List<String>,
    val createdAt: Long,
)

/** Citations and notes are stored in the database as JSON text. Reading is forgiving: unreadable JSON gives an empty list. */
object ChatCodec {
    fun encodeCitations(list: List<AskCitation>): String {
        val array = JSONArray()
        list.forEach { c ->
            val o = JSONObject()
            o.put("n", c.number)
            o.put("chunk", c.chunkId)
            o.put("source", c.sourceId)
            o.put("title", c.sourceTitle)
            o.put("kind", c.sourceKind.name)
            c.url?.let { o.put("url", it) }
            o.put("from", c.startLine)
            o.put("to", c.endLine)
            o.put("text", c.excerpt)
            o.put("basis", c.basis.name)
            c.insightId?.let { o.put("insight", it) }
            array.put(o)
        }
        return array.toString()
    }

    fun decodeCitations(json: String): List<AskCitation> {
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            AskCitation(
                number = o.optInt("n"),
                chunkId = o.optString("chunk"),
                sourceId = o.optString("source"),
                sourceTitle = o.optString("title"),
                sourceKind = runCatching { SourceKind.valueOf(o.optString("kind")) }.getOrDefault(SourceKind.TEXT),
                url = if (o.has("url") && !o.isNull("url")) o.getString("url") else null,
                startLine = o.optInt("from"),
                endLine = o.optInt("to"),
                excerpt = o.optString("text"),
                basis = runCatching { CitationBasis.valueOf(o.optString("basis")) }.getOrDefault(CitationBasis.TEXT),
                insightId = if (o.has("insight") && !o.isNull("insight")) o.getString("insight") else null,
            )
        }
    }

    fun encodeNotes(list: List<String>): String = JSONArray(list).toString()

    fun decodeNotes(json: String): List<String> {
        val array = try {
            JSONArray(json)
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
    }
}

/** At most [max] characters, never cutting a surrogate pair in half. */
internal fun clipText(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}

/** Text that is safe inside a double-quoted prompt attribute: quotes become apostrophes and line breaks become spaces. */
internal fun promptAttribute(text: String): String = text.replace('"', '\'').replace(Regex("\\s+"), " ").trim()
