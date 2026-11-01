package ai.lily.app.lib.editor

/** [newText] is the file after replacing, [count] is how many matches were replaced. */
data class FileReplace(val newText: String, val count: Int)

/** True if the start of [text] has a NUL character, which text files do not have. */
fun isProbablyBinary(text: String): Boolean {
    val end = minOf(text.length, 2_000)
    for (i in 0 until end) if (text[i] == '\u0000') return true
    return false
}

/**
 * Replaces every non-empty match of [regex] in [text]. Returns null if there was nothing to replace.
 * Matches that are empty (a pattern like `x*` can match nothing) are left alone. In plain mode the replacement is literal,
 * in regex mode `$1` is a group. Throws IllegalArgumentException or IndexOutOfBoundsException for a bad group reference.
 */
fun planReplace(text: String, regex: Regex, replacement: String, regexMode: Boolean): FileReplace? {
    val matcher = regex.toPattern().matcher(text)
    val out = StringBuffer(text.length + 16)
    val rep = replacementFor(replacement, regexMode)
    var count = 0
    while (matcher.find()) {
        if (matcher.end() == matcher.start()) continue
        count++
        matcher.appendReplacement(out, rep)
    }
    if (count == 0) return null
    matcher.appendTail(out)
    return FileReplace(out.toString(), count)
}
