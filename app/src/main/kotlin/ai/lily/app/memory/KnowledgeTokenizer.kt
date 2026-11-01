package ai.lily.app.memory

/** Splits text into lower-case words (letters and digits, in any language) for keyword search. */
object KnowledgeTokenizer {
    const val MAX_QUERY_TERMS = 12
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    fun words(text: String): List<String> = text.lowercase().split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * The words to look for: each only once, at most [MAX_QUERY_TERMS], in the order they were typed. A one-letter English word
     * carries no meaning, so it is dropped; a single character from another script (a Chinese character, say) is kept.
     */
    fun queryTerms(query: String): List<String> =
        words(query).filter { it.length > 1 || it[0].code > 127 }.distinct().take(MAX_QUERY_TERMS)
}
