package ai.lily.app.lib.editor

// A small Markdown to HTML converter for the preview. It covers what people write in README files:
// headings, paragraphs, bold, italic, strikethrough, inline code, fenced code, links, images, block quotes,
// bullet, numbered and task lists (one level of nesting per indent), tables and rules.
// Any HTML in the Markdown is shown as text, not run, and links that are not http, https, mailto or tel are made inert.

private val FENCE = Regex("^(```+|~~~+)\\s*([\\w+#.-]*)")
private val HEADING = Regex("^(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$")
private val RULE = Regex("^(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")
private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

private val CODE_SPAN = Regex("(`+)(.+?)\\1")
private val IMAGE = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+&quot;(.*?)&quot;)?\\)")
private val LINK = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)(?:\\s+&quot;(.*?)&quot;)?\\)")
private val BOLD_STAR = Regex("\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*")
private val BOLD_UNDER = Regex("(?<!\\w)__(?=\\S)(.+?)(?<=\\S)__(?!\\w)")
private val ITALIC_STAR = Regex("\\*(?=\\S)(.+?)(?<=\\S)\\*")
private val ITALIC_UNDER = Regex("(?<!\\w)_(?=\\S)(.+?)(?<=\\S)_(?!\\w)")
private val STRIKE = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")
private val HELD = Regex("\u0003(\\d+)\u0003")
private val CODE_HELD = Regex("\u0002(\\d+)\u0002")
private val ALLOWED_SCHEMES = setOf("http", "https", "mailto", "tel")

// Stand-ins used while a line is being converted: a hard line break, a protected piece of HTML, a protected code span.
private const val HARD_BREAK = '\u0001'

private fun esc(s: String): String = buildString(s.length + 16) {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}

/** Converts Markdown to the HTML that goes inside the page's body. */
fun markdownToPreviewHtml(source: String): String =
    renderBlocks(source.replace("\r\n", "\n").replace('\r', '\n').split('\n'))

private fun indentOf(line: String): Int =
    line.takeWhile { it == ' ' || it == '\t' }.fold(0) { acc, c -> acc + if (c == '\t') 4 else 1 }

private fun splitRow(row: String): List<String> {
    var s = row.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split('|').map { it.trim() }
}

private fun isTableStart(lines: List<String>, i: Int): Boolean =
    lines[i].contains('|') && i + 1 < lines.size && lines[i + 1].contains('|') && TABLE_SEP.matches(lines[i + 1])

/** True when the line at [i] begins something other than a plain paragraph. */
private fun startsBlock(lines: List<String>, i: Int): Boolean {
    val line = lines[i]
    val t = line.trim()
    return FENCE.find(t) != null ||
        HEADING.find(t) != null ||
        RULE.matches(t) ||
        t.startsWith(">") ||
        LIST_ITEM.matches(line) ||
        isTableStart(lines, i)
}

private fun renderBlocks(lines: List<String>): String {
    val out = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        if (t.isEmpty()) {
            i++
            continue
        }

        val fence = FENCE.find(t)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val lang = fence.groupValues[2]
            val body = ArrayList<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(marker)) {
                body += lines[i]
                i++
            }
            i++
            out.append("<pre><code")
            if (lang.isNotEmpty()) out.append(" class=\"language-").append(esc(lang)).append('"')
            out.append('>').append(esc(body.joinToString("\n"))).append("</code></pre>\n")
            continue
        }

        val heading = HEADING.find(t)
        if (heading != null) {
            val level = heading.groupValues[1].length
            out.append("<h").append(level).append('>').append(inline(heading.groupValues[2]))
                .append("</h").append(level).append(">\n")
            i++
            continue
        }

        if (RULE.matches(t)) {
            out.append("<hr>\n")
            i++
            continue
        }

        if (t.startsWith(">")) {
            val quote = ArrayList<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quote += lines[i].trim().removePrefix(">").removePrefix(" ")
                i++
            }
            out.append("<blockquote>\n").append(renderBlocks(quote)).append("</blockquote>\n")
            continue
        }

        if (LIST_ITEM.matches(line)) {
            val block = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && (LIST_ITEM.matches(lines[i]) || indentOf(lines[i]) >= 2)) {
                block += lines[i]
                i++
            }
            out.append(renderList(block))
            continue
        }

        if (isTableStart(lines, i)) {
            val header = splitRow(t)
            val aligns = splitRow(lines[i + 1].trim()).map { c ->
                when {
                    c.startsWith(":") && c.endsWith(":") -> "center"
                    c.endsWith(":") -> "right"
                    c.startsWith(":") -> "left"
                    else -> ""
                }
            }
            i += 2
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                rows += splitRow(lines[i].trim())
                i++
            }
            fun cell(tag: String, text: String, col: Int): String {
                val align = aligns.getOrNull(col).orEmpty()
                val style = if (align.isEmpty()) "" else " style=\"text-align:$align\""
                return "<$tag$style>${inline(text)}</$tag>"
            }
            out.append("<table><thead><tr>")
            header.forEachIndexed { col, text -> out.append(cell("th", text, col)) }
            out.append("</tr></thead><tbody>")
            for (row in rows) {
                out.append("<tr>")
                row.forEachIndexed { col, text -> out.append(cell("td", text, col)) }
                out.append("</tr>")
            }
            out.append("</tbody></table>\n")
            continue
        }

        val para = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank() && (para.isEmpty() || !startsBlock(lines, i))) {
            para += lines[i]
            i++
        }
        val sb = StringBuilder()
        para.forEachIndexed { idx, l ->
            val hard = l.endsWith("  ") || l.endsWith("\\")
            sb.append(l.trim().removeSuffix("\\").trimEnd())
            if (idx < para.lastIndex) sb.append(if (hard) HARD_BREAK else ' ')
        }
        out.append("<p>").append(inline(sb.toString())).append("</p>\n")
    }
    return out.toString()
}

private fun renderList(lines: List<String>): String {
    val base = indentOf(lines.first())
    val ordered = LIST_ITEM.find(lines.first())?.groupValues?.get(2)?.firstOrNull()?.isDigit() == true
    val texts = ArrayList<String>()
    val subs = ArrayList<MutableList<String>>()
    for (l in lines) {
        val m = LIST_ITEM.find(l)
        if (m != null && indentOf(l) <= base) {
            texts += m.groupValues[3]
            subs += mutableListOf<String>()
        } else if (subs.isNotEmpty()) {
            subs.last() += l
        }
    }
    val tag = if (ordered) "ol" else "ul"
    val sb = StringBuilder("<$tag>")
    for (k in texts.indices) {
        val sub = subs[k]
        val nestedAt = sub.indexOfFirst { LIST_ITEM.matches(it) }
        val own = if (nestedAt >= 0) sub.subList(0, nestedAt) else sub
        val nested = if (nestedAt >= 0) sub.subList(nestedAt, sub.size) else emptyList()
        val text = (listOf(texts[k]) + own.map { it.trim() }).joinToString(" ")
        sb.append("<li>").append(itemHtml(text))
        if (nested.isNotEmpty()) sb.append(renderList(nested))
        sb.append("</li>")
    }
    sb.append("</$tag>\n")
    return sb.toString()
}

private fun itemHtml(text: String): String = when {
    text.startsWith("[ ] ") -> "<input type=\"checkbox\" disabled> " + inline(text.substring(4))
    text.startsWith("[x] ", ignoreCase = true) -> "<input type=\"checkbox\" disabled checked> " + inline(text.substring(4))
    else -> inline(text)
}

private fun safeUrl(url: String): String {
    val u = url.trim()
    val scheme = SCHEME.find(u)?.groupValues?.get(1)?.lowercase() ?: return u
    return if (scheme in ALLOWED_SCHEMES) u else "#"
}

private fun emphasis(s: String): String {
    var r = BOLD_STAR.replace(s) { "<strong>${it.groupValues[1]}</strong>" }
    r = BOLD_UNDER.replace(r) { "<strong>${it.groupValues[1]}</strong>" }
    r = ITALIC_STAR.replace(r) { "<em>${it.groupValues[1]}</em>" }
    r = ITALIC_UNDER.replace(r) { "<em>${it.groupValues[1]}</em>" }
    r = STRIKE.replace(r) { "<del>${it.groupValues[1]}</del>" }
    return r
}

/** Code spans, images and links are set aside first so that underscores in a URL or `code` are never read as emphasis. */
private fun inline(raw: String): String {
    val codes = ArrayList<String>()
    var s = CODE_SPAN.replace(raw) { m ->
        codes += "<code>" + esc(m.groupValues[2].trim()) + "</code>"
        "\u0002" + (codes.size - 1) + "\u0002"
    }
    s = esc(s)
    val held = ArrayList<String>()
    fun hold(html: String): String {
        held += html
        return "\u0003" + (held.size - 1) + "\u0003"
    }
    s = IMAGE.replace(s) { m ->
        val title = m.groups[3]?.value
        hold(
            "<img src=\"" + safeUrl(m.groupValues[2]) + "\" alt=\"" + m.groupValues[1] + "\"" +
                (if (title != null) " title=\"$title\"" else "") + ">",
        )
    }
    s = LINK.replace(s) { m ->
        val title = m.groups[3]?.value
        hold(
            "<a href=\"" + safeUrl(m.groupValues[2]) + "\"" + (if (title != null) " title=\"$title\"" else "") + ">" +
                emphasis(m.groupValues[1]) + "</a>",
        )
    }
    s = emphasis(s)
    var rounds = 0
    while (s.indexOf('\u0003') >= 0 && rounds < 5) {
        s = HELD.replace(s) { held[it.groupValues[1].toInt()] }
        rounds++
    }
    s = CODE_HELD.replace(s) { codes[it.groupValues[1].toInt()] }
    return s.replace(HARD_BREAK.toString(), "<br>\n")
}
