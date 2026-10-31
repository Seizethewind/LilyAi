package ai.lily.app.lib.editor

/** What the find bar is looking for. */
data class FindOptions(
    val query: String,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/** More matches than this are not listed, so a one letter search in a big file stays quick. */
const val MAX_MATCHES = 5_000

/** Builds the regex for [o]. Throws IllegalArgumentException when a regex pattern is invalid. */
fun buildFindRegex(o: FindOptions): Regex {
    val body = if (o.regex) o.query else Regex.escape(o.query)
    val pattern = if (o.wholeWord) "\\b(?:$body)\\b" else body
    return if (o.caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
}

/** Every non-empty match of [regex] in [text], up to [MAX_MATCHES]. */
fun findMatches(text: String, regex: Regex): List<IntRange> {
    val out = ArrayList<IntRange>()
    for (m in regex.findAll(text)) {
        if (m.value.isEmpty()) continue
        out += m.range
        if (out.size >= MAX_MATCHES) break
    }
    return out
}

/** Plain text replacements are literal, so "$1" in the box means a dollar sign and a one. In regex mode it is a group. */
fun replacementFor(replacement: String, regexMode: Boolean): String =
    if (regexMode) replacement else Regex.escapeReplacement(replacement)

/**
 * What the match at [range] turns into. Null if there is no match starting there any more.
 * Regex groups ($1) work in regex mode.
 */
fun replaceOne(text: String, regex: Regex, range: IntRange, replacement: String, regexMode: Boolean): String? {
    val matcher = regex.toPattern().matcher(text)
    if (!matcher.find(range.first) || matcher.start() != range.first) return null
    val sb = StringBuffer()
    matcher.appendReplacement(sb, replacementFor(replacement, regexMode))
    return sb.substring(range.first)
}

/** Replaces every match. */
fun replaceEvery(text: String, regex: Regex, replacement: String, regexMode: Boolean): String =
    regex.replace(text, replacementFor(replacement, regexMode))
