package ai.lily.app.memory

import org.json.JSONArray
import org.json.JSONException

/** Turns a question into a few short searches. The model's reply is read tolerantly: it may add words around the list. */
object QueryPlanner {
    const val MAX_QUERIES = 3
    private const val MAX_QUERY_LENGTH = 200

    const val SYSTEM = "You turn a question into short search queries for a personal library of notes and web pages. " +
        "Reply with only a JSON array of 1 to 3 short strings. No other text."

    fun prompt(question: String) = "Question: $question\n\nSearch queries (a JSON array of strings):"

    /** The queries in the reply, trimmed, without blanks or repeats, at most [MAX_QUERIES]. Empty if the reply holds no list. */
    fun parse(reply: String): List<String> {
        val start = reply.indexOf('[')
        val end = reply.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = try {
            JSONArray(reply.substring(start, end + 1))
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until array.length())
            .mapNotNull { (array.opt(it) as? String)?.trim()?.take(MAX_QUERY_LENGTH)?.takeIf { s -> s.isNotEmpty() } }
            .distinct()
            .take(MAX_QUERIES)
    }
}
