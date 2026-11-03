package ai.lily.app.research

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.memory.KnowledgeText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class SearchResult(val title: String, val url: String, val snippet: String)

interface SearchProvider {
    suspend fun search(query: String, limit: Int): List<SearchResult>
}

/**
 * Web search through the router's search-capable model (`RouterConfig.webSearchModel`, as the `web_search` tool does).
 *
 * The model is asked for a JSON list of `{title, url, snippet}`. If it answers in prose instead, the web addresses in the prose
 * are used. Either way every address goes through [KnowledgeText.normaliseUrl], so only http and https addresses without a
 * username or password survive, and repeats are dropped.
 *
 * **What this is not:** a search index. The list comes from a model and can hold addresses that do not exist or that do not say
 * what the snippet claims. That is why research only keeps what it actually fetched: the saved source is the page's own text, and
 * the snippet is never stored or shown to the answering model.
 */
class ModelSearchProvider(private val gateway: ModelGateway, private val model: String?) : SearchProvider {

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val asked = query.trim()
        require(asked.isNotEmpty()) { "Search for something." }
        val reply = gateway.complete(ModelRequest(prompt(asked, limit), SYSTEM, model))
        return parse(reply.text, limit)
    }

    companion object {
        const val SYSTEM = "You search the web. Reply with only a JSON array of objects with the keys title, url and snippet. " +
            "Use real addresses that the search returned and never invent one. No other text."

        private const val TITLE_LENGTH = 200
        private const val SNIPPET_LENGTH = 300
        private val ADDRESS = Regex("https?://[^\\s<>\"')\\]]+", RegexOption.IGNORE_CASE)

        fun prompt(query: String, limit: Int) = "Search the web for: $query\n\nGive up to $limit results as a JSON array."

        /** The results in the reply: from its JSON list if there is one, otherwise from the addresses in its text. At most [limit]. */
        fun parse(reply: String, limit: Int): List<SearchResult> {
            val fromJson = fromJson(reply)
            val found = if (fromJson.isNotEmpty()) fromJson else fromAddresses(reply)
            return found.take(limit.coerceAtLeast(0))
        }

        private fun fromJson(reply: String): List<SearchResult> {
            val start = reply.indexOf('[')
            val end = reply.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val array = try {
                JSONArray(reply.substring(start, end + 1))
            } catch (e: JSONException) {
                return emptyList()
            }
            val seen = LinkedHashSet<String>()
            val results = ArrayList<SearchResult>()
            for (i in 0 until array.length()) {
                val item = array.opt(i) as? JSONObject ?: continue
                val address = normalise(item.optString("url", "")) ?: continue
                if (!seen.add(address)) continue
                val title = item.optString("title", "").trim().take(TITLE_LENGTH).ifEmpty { address }
                results += SearchResult(title, address, item.optString("snippet", "").trim().take(SNIPPET_LENGTH))
            }
            return results
        }

        private fun fromAddresses(reply: String): List<SearchResult> {
            val seen = LinkedHashSet<String>()
            val results = ArrayList<SearchResult>()
            for (m in ADDRESS.findAll(reply)) {
                val address = normalise(m.value.trimEnd('.', ',', ';', ':', '!', '?')) ?: continue
                if (seen.add(address)) results += SearchResult(address, address, "")
            }
            return results
        }

        private fun normalise(raw: String): String? = try {
            KnowledgeText.normaliseUrl(raw)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/** Turns a research question into a few web searches. Read tolerantly: the model may add words around the list. */
object ResearchPlanner {
    const val MAX_QUERIES = 4
    private const val MAX_QUERY_LENGTH = 200

    const val SYSTEM = "You turn a research question into short web search queries that together cover it from different angles. " +
        "Reply with only a JSON array of 1 to $MAX_QUERIES short strings. No other text."

    fun prompt(question: String) = "Research question: $question\n\nWeb search queries (a JSON array of strings):"

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
