package ai.lily.app.research

/** The readable text of a page. [truncated] is true if it was cut at the size limit. */
data class ExtractedPage(val title: String, val text: String, val truncated: Boolean)

/**
 * Turns a web page into plain text, keeping its line structure (research cites line ranges, so lines must stay stable).
 *
 * Removed with their contents: scripts, styles, `noscript`, `template`, `svg`, `iframe`, `object`, `head` and comments. Block tags
 * become line breaks, other tags vanish, character references are decoded, runs of spaces collapse and more than one blank line
 * collapses to one.
 *
 * Known limits: this is a text filter, not a browser. It does not run scripts, so pages that build their text in JavaScript come
 * out empty. It does not know what is hidden by CSS, so text hidden from a reader (a common place for injected instructions) can
 * still appear; that is why everything extracted is stored as untrusted source text. An element that is never closed (a
 * `<script>` with no end tag) leaves its contents behind as text.
 */
object HtmlExtractor {
    private const val TITLE_LENGTH = 200

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val TITLE = Regex("<title\\b[^>]*>(.*?)</title\\s*>", OPTIONS)
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val DROPPED = Regex(
        "<(script|style|noscript|template|svg|iframe|object|head)\\b[^>]*>.*?</\\1\\s*>",
        OPTIONS,
    )
    private val BREAKS = Regex(
        "</?(p|div|br|li|ul|ol|tr|table|h[1-6]|section|article|header|footer|main|pre|blockquote|hr|dd|dt|dl)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val CELLS = Regex("</?(td|th)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("</?[a-zA-Z][^>]*>")
    private val REFERENCE = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val SPACES = Regex("[ \\t\\u00a0\\u200b]+")
    private val BLANK_RUNS = Regex("\\n{3,}")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026", "lsquo" to "\u2018", "rsquo" to "\u2019",
        "ldquo" to "\u201c", "rdquo" to "\u201d", "copy" to "\u00a9", "reg" to "\u00ae", "trade" to "\u2122",
        "bull" to "\u2022", "middot" to "\u00b7", "laquo" to "\u00ab", "raquo" to "\u00bb", "euro" to "\u20ac",
    )

    fun extract(html: String, maxChars: Int = 60_000): ExtractedPage {
        require(maxChars > 0) { "The size limit must be positive." }
        val title = TITLE.find(html)?.groupValues?.get(1)?.let { collapse(decode(TAGS.replace(it, ""))) }.orEmpty().take(TITLE_LENGTH)

        var s = COMMENT.replace(html, "")
        s = DROPPED.replace(s, "")
        s = BREAKS.replace(s, "\n")
        s = CELLS.replace(s, " ")
        s = TAGS.replace(s, "")
        s = decode(s)
        return finish(title, s, maxChars)
    }

    /** Plain text needs no tag removal, only tidying and the size limit. */
    fun fromPlainText(text: String, maxChars: Int = 60_000): ExtractedPage {
        require(maxChars > 0) { "The size limit must be positive." }
        return finish("", text, maxChars)
    }

    private fun finish(title: String, raw: String, maxChars: Int): ExtractedPage {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { SPACES.replace(it, " ").trim() }
        val tidy = BLANK_RUNS.replace(lines.joinToString("\n"), "\n\n").trim()
        if (tidy.length <= maxChars) return ExtractedPage(title, tidy, truncated = false)
        var end = maxChars
        if (Character.isHighSurrogate(tidy[end - 1])) end -= 1
        return ExtractedPage(title, tidy.substring(0, end).trimEnd(), truncated = true)
    }

    private fun collapse(s: String) = SPACES.replace(s.replace('\n', ' '), " ").trim()

    private fun decode(s: String): String = REFERENCE.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16)) ?: m.value
            body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull()) ?: m.value
            else -> NAMED[body.lowercase()] ?: m.value
        }
    }

    private fun codePoint(value: Int?): String? =
        if (value != null && value in 1..0x10FFFF && !(value in 0xD800..0xDFFF)) String(Character.toChars(value)) else null
}
