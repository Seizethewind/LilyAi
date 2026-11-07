package ai.lily.app.tools

import org.json.JSONArray

/** Flattens text that came from a page to one line and cuts it. */
internal fun flatten(s: String, max: Int): String = s.replace(Regex("\\s+"), " ").trim().take(max)

/** Reads the list of things a page offers to click or type into, and writes it out for a model. */
object PageElements {
    private const val MAX = 60

    /** Null when [json] is missing or is not a list. Every string is flattened to one line and cut, because the page wrote it. */
    fun parse(json: String?): List<PageElement>? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val arr = JSONArray(json)
            (0 until minOf(arr.length(), MAX)).map { n ->
                val o = arr.getJSONObject(n)
                val type = flatten(o.optString("type"), 12)
                val detail = listOfNotNull(
                    flatten(o.optString("hint"), 40).takeIf { it.isNotEmpty() }?.let { "hint \"$it\"" },
                    flatten(o.optString("value"), 40).takeIf { it.isNotEmpty() }?.let { "now \"$it\"" },
                    flatten(o.optString("href"), 60).takeIf { it.isNotEmpty() }?.let { "to $it" },
                ).joinToString(", ")
                PageElement(
                    index = o.getInt("i"),
                    kind = flatten(o.optString("tag"), 12) + if (type.isNotEmpty()) "[$type]" else "",
                    label = flatten(o.optString("label"), 40),
                    detail = detail,
                )
            }
        }.getOrNull()
    }

    /** One line per item: [number] kind "name" (details). */
    fun format(elements: List<PageElement>): String =
        if (elements.isEmpty()) {
            "(nothing on the page can be clicked or typed into)"
        } else {
            elements.joinToString("\n") { e -> "[${e.index}] ${e.kind} \"${e.label}\"" + if (e.detail.isNotEmpty()) " (${e.detail})" else "" }
        }
}
