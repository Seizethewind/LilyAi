package ai.lily.app.lib.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

// Pure helpers for editing text the way a code editor does. They take a value and give back a new one.

/** Start of the line that holds [pos]. */
fun lineStartOffset(text: String, pos: Int): Int = text.lastIndexOf('\n', pos - 1) + 1

/** End of the line that holds [pos] (the index of its newline, or the end of the text). */
fun lineEndOffset(text: String, pos: Int): Int {
    val i = text.indexOf('\n', pos)
    return if (i < 0) text.length else i
}

/** Zero based line number of [offset]. */
fun lineNumber(text: String, offset: Int): Int {
    var count = 0
    val end = offset.coerceIn(0, text.length)
    for (i in 0 until end) if (text[i] == '\n') count++
    return count
}

/** One based line and column of [offset], for the status line. */
fun lineColumn(text: String, offset: Int): Pair<Int, Int> {
    val at = offset.coerceIn(0, text.length)
    return Pair(lineNumber(text, at) + 1, at - lineStartOffset(text, at) + 1)
}

/** The indent a file already uses: a tab, 2 spaces or 4 spaces. Empty or unclear files get 4 spaces. */
fun detectIndent(text: String): String {
    var tabbed = 0
    var spaced = 0
    var smallest = Int.MAX_VALUE
    var seen = 0
    for (line in text.lineSequence()) {
        if (seen >= 300) break
        if (line.isBlank()) continue
        seen++
        if (line.startsWith("\t")) {
            tabbed++
            continue
        }
        val n = line.takeWhile { it == ' ' }.length
        if (n in 1..8) {
            spaced++
            smallest = minOf(smallest, n)
        }
    }
    return when {
        tabbed > spaced -> "\t"
        smallest == Int.MAX_VALUE -> "    "
        smallest <= 2 -> "  "
        else -> "    "
    }
}

private val COLON_BLOCK_LANGS = setOf(Lang.PYTHON, Lang.YAML)

private fun closerFor(open: Char): Char = when (open) {
    '(' -> ')'
    '[' -> ']'
    else -> '}'
}

/**
 * Looks at one typed character and, where an editor would, improves on it:
 * Enter keeps the indent of the line above (and opens a block after { ( [ or a Python colon),
 * an opening bracket or quote gets its partner, and typing a closer that is already there steps over it.
 * With [autoPair] off only the Enter behaviour is kept, so brackets and quotes are left exactly as typed.
 * Returns null when the edit should stay as typed.
 */
fun smartEdit(
    old: TextFieldValue,
    new: TextFieldValue,
    lang: Lang,
    indent: String,
    autoPair: Boolean = true,
): TextFieldValue? {
    if (new.composition != null) return null
    if (!old.selection.collapsed || !new.selection.collapsed) return null
    val oldText = old.text
    val newText = new.text
    if (newText.length != oldText.length + 1) return null
    val p = new.selection.start - 1
    if (p < 0 || old.selection.start != p) return null
    if (!oldText.regionMatches(0, newText, 0, p)) return null
    if (!oldText.regionMatches(p, newText, p + 1, oldText.length - p)) return null

    val ch = newText[p]
    val next = if (p < oldText.length) oldText[p] else '\u0000'
    val prev = if (p > 0) oldText[p - 1] else '\u0000'
    val code = lang != Lang.PLAIN && lang != Lang.MARKDOWN

    if (ch == '\n') {
        val lineBefore = oldText.substring(lineStartOffset(oldText, p), p)
        val base = lineBefore.takeWhile { it == ' ' || it == '\t' }
        val trimmed = lineBefore.trimEnd()
        val last = trimmed.lastOrNull()
        val opens = (last != null && "{([".indexOf(last) >= 0) || (lang in COLON_BLOCK_LANGS && last == ':')
        val extra = if (opens) indent else ""
        val closeNext = opens && next != '\u0000' && "}])".indexOf(next) >= 0
        val inserted = if (closeNext) "\n$base$extra\n$base" else "\n$base$extra"
        val text = oldText.substring(0, p) + inserted + oldText.substring(p)
        return TextFieldValue(text, TextRange(p + 1 + base.length + extra.length))
    }
    if (!code || !autoPair) return null
    if (")]}".indexOf(ch) >= 0 && next == ch) return TextFieldValue(oldText, TextRange(p + 1))
    if ("([{".indexOf(ch) >= 0) {
        val room = next == '\u0000' || next.isWhitespace() || ")]},;".indexOf(next) >= 0
        if (!room) return null
        val text = oldText.substring(0, p) + ch + closerFor(ch) + oldText.substring(p)
        return TextFieldValue(text, TextRange(p + 1))
    }
    if (ch == '"' || ch == '\'' || ch == '`') {
        if (next == ch) return TextFieldValue(oldText, TextRange(p + 1))
        if (prev.isLetterOrDigit() || next.isLetterOrDigit()) return null
        if (ch == '\'' && lang == Lang.RUST) return null
        val text = oldText.substring(0, p) + ch + ch + oldText.substring(p)
        return TextFieldValue(text, TextRange(p + 1))
    }
    return null
}

/** Replaces the selection with [s] and puts the cursor after it. */
fun insertAt(v: TextFieldValue, s: String): TextFieldValue {
    val sel = v.selection
    val text = v.text.replaceRange(sel.min, sel.max, s)
    return TextFieldValue(text, TextRange(sel.min + s.length))
}

private class Block(val start: Int, val end: Int, val lines: List<String>)

/** The whole lines touched by the selection. */
private fun selectedBlock(v: TextFieldValue): Block {
    val text = v.text
    val sel = v.selection
    val start = lineStartOffset(text, sel.min)
    val endPos = if (sel.max > sel.min && text[sel.max - 1] == '\n') sel.max - 1 else sel.max
    val end = lineEndOffset(text, endPos)
    return Block(start, end, text.substring(start, end).split('\n'))
}

private fun replaceBlock(v: TextFieldValue, b: Block, newLines: List<String>): TextFieldValue {
    val firstDelta = newLines.first().length - b.lines.first().length
    val total = newLines.sumOf { it.length } - b.lines.sumOf { it.length }
    val text = v.text.substring(0, b.start) + newLines.joinToString("\n") + v.text.substring(b.end)
    val sel = v.selection
    val start = maxOf(b.start, sel.min + firstDelta).coerceAtMost(text.length)
    val end = maxOf(start, sel.max + total).coerceAtMost(text.length)
    return TextFieldValue(text, TextRange(start, end))
}

/** Tab and Shift+Tab. With nothing selected, Tab just inserts one indent. */
fun shiftIndent(v: TextFieldValue, indent: String, unindent: Boolean): TextFieldValue {
    if (!unindent && v.selection.collapsed) return insertAt(v, indent)
    val b = selectedBlock(v)
    val lines = b.lines.map { line ->
        if (unindent) {
            val remove = when {
                line.startsWith(indent) -> indent.length
                line.startsWith("\t") -> 1
                else -> minOf(indent.length, line.takeWhile { it == ' ' }.length)
            }
            line.substring(remove)
        } else if (line.isBlank()) {
            line
        } else {
            indent + line
        }
    }
    return replaceBlock(v, b, lines)
}

fun duplicateLines(v: TextFieldValue): TextFieldValue {
    val b = selectedBlock(v)
    val block = v.text.substring(b.start, b.end)
    val text = v.text.substring(0, b.end) + "\n" + block + v.text.substring(b.end)
    val shift = block.length + 1
    return TextFieldValue(text, TextRange(v.selection.min + shift, v.selection.max + shift))
}

fun deleteLines(v: TextFieldValue): TextFieldValue {
    val b = selectedBlock(v)
    val t = v.text
    val from: Int
    val to: Int
    when {
        b.end < t.length -> {
            from = b.start
            to = b.end + 1
        }
        b.start > 0 -> {
            from = b.start - 1
            to = b.end
        }
        else -> {
            from = b.start
            to = b.end
        }
    }
    val text = t.removeRange(from, to)
    return TextFieldValue(text, TextRange(minOf(from, text.length)))
}

/** Comments the selected lines out, or back in if they are all commented already. */
fun toggleLineComment(v: TextFieldValue, prefix: String): TextFieldValue {
    val b = selectedBlock(v)
    val nonBlank = b.lines.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) return v
    val allCommented = nonBlank.all { it.trimStart().startsWith(prefix) }
    val minIndent = nonBlank.minOf { it.length - it.trimStart().length }
    val lines = b.lines.map { line ->
        when {
            line.isBlank() -> line
            allCommented -> {
                val at = line.length - line.trimStart().length
                line.substring(0, at) + line.substring(at + prefix.length).removePrefix(" ")
            }
            else -> line.substring(0, minIndent) + prefix + " " + line.substring(minIndent)
        }
    }
    return replaceBlock(v, b, lines)
}

/** Moves the cursor to the start of 1 based [line], or to the last line if there are fewer. */
fun gotoLine(v: TextFieldValue, line: Int): TextFieldValue {
    val t = v.text
    var pos = 0
    var l = 1
    while (l < line) {
        val i = t.indexOf('\n', pos)
        if (i < 0) break
        pos = i + 1
        l++
    }
    return TextFieldValue(t, TextRange(pos))
}
