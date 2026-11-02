package ai.lily.app.codeindex

/**
 * Splits code and questions into search terms.
 *
 * An identifier gives its lower-case whole form and its parts, split at underscores and at changes of case, so `parseReply`,
 * `parse_reply` and the question "where is the reply parsed" can all reach each other: `parsereply`, `parse`, `reply`.
 * `HTTPServer` gives `httpserver`, `http`, `server`. Single characters and pure numbers are dropped.
 */
object CodeTokenizer {
    private val WORD = Regex("""[\p{L}\p{N}_]+""")
    private val CAMEL = Regex("""(?<=[\p{Ll}\p{N}])(?=\p{Lu})|(?<=\p{Lu})(?=\p{Lu}\p{Ll})""")

    /** Words that say what to do, not where to find it. Removed from questions only, never from the code. */
    private val STOP = setOf(
        "the", "an", "and", "or", "to", "of", "in", "on", "for", "with", "this", "that", "it", "is", "be", "as", "at", "by", "from",
        "into", "please", "can", "you", "make", "add", "change", "update", "fix", "file", "files", "code", "function", "so", "my",
        "me", "we", "how", "what", "where", "why", "when", "do", "does", "should", "need", "want", "use", "using",
    )

    /** Every term in [text], repeats included (so they can be counted). */
    fun terms(text: String): List<String> {
        val out = ArrayList<String>()
        for (m in WORD.findAll(text)) addWord(m.value, out)
        return out
    }

    /** The distinct terms of a question, without words that only say what to do. */
    fun queryTerms(text: String): List<String> = terms(text).filter { it !in STOP }.distinct()

    private fun addWord(word: String, out: MutableList<String>) {
        val parts = word.split('_')
            .filter { it.isNotEmpty() }
            .flatMap { it.split(CAMEL) }
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        if (parts.size == 1) {
            keep(parts[0], out)
            return
        }
        keep(parts.joinToString(""), out)
        for (p in parts) keep(p, out)
    }

    private fun keep(term: String, out: MutableList<String>) {
        if (term.length >= 2 && term.any { it.isLetter() }) out += term
    }
}
