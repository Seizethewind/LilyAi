package ai.lily.app.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the audit script found on a page. Strings in it come from the page, so they are flattened to one line and cut short.
 * The script reports several checks, each with how many elements it looked at, how many failed and a few examples.
 */
data class AuditReport(
    val width: Int,
    val height: Int,
    val lang: Boolean,
    val viewportMeta: Boolean,
    val h1: Int,
    val overflowX: Boolean,
    val checks: List<Check>,
) {
    data class Check(val id: String, val checked: Int, val bad: Int, val examples: List<String>)

    /** The report as plain text for a model to read. */
    fun toText(): String = buildString {
        append("Layout width ${width} px, height ${height} px.\n")
        append("Basics: viewport tag ${yes(viewportMeta)}; page language set ${yes(lang)}; h1 headings $h1; sideways scrolling ${yes(overflowX)}.")
        for (c in checks) {
            append("\n- ").append(NAMES[c.id] ?: c.id).append(": ")
            if (c.bad == 0) {
                append("none of ${c.checked} checked")
            } else {
                append("${c.bad} of ${c.checked}")
                if (c.examples.isNotEmpty()) append(" (e.g. ").append(c.examples.joinToString("; ")).append(')')
            }
        }
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    companion object {
        private const val MAX_CHECKS = 20
        private const val MAX_EXAMPLES = 5
        private const val MAX_EXAMPLE_CHARS = 80

        private val NAMES = mapOf(
            "image-alt" to "Images without alt text",
            "field-label" to "Form fields without a label",
            "empty-link" to "Links or buttons with no readable name",
            "tap-target" to "Tap targets smaller than 44 x 44 px",
            "small-text" to "Text smaller than 12 px",
            "contrast" to "Text with low contrast (approximate)",
        )

        /** Null when [json] is missing, is not what the script returns, or is the script's own error answer. */
        fun parse(json: String?): AuditReport? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                val arr = o.getJSONArray("checks")
                AuditReport(
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                    lang = o.optBoolean("lang"),
                    viewportMeta = o.optBoolean("viewportMeta"),
                    h1 = o.optInt("h1"),
                    overflowX = o.optBoolean("overflowX"),
                    checks = (0 until minOf(arr.length(), MAX_CHECKS)).map { i ->
                        val c = arr.getJSONObject(i)
                        val ex = c.optJSONArray("examples") ?: JSONArray()
                        Check(
                            id = oneLine(c.optString("id"), 40),
                            checked = c.optInt("checked"),
                            bad = c.optInt("bad"),
                            examples = (0 until minOf(ex.length(), MAX_EXAMPLES)).map { oneLine(ex.optString(it), MAX_EXAMPLE_CHARS) },
                        )
                    },
                )
            }.getOrNull()
        }

        private fun oneLine(s: String, max: Int): String = s.replace(Regex("\\s+"), " ").trim().take(max)
    }
}
