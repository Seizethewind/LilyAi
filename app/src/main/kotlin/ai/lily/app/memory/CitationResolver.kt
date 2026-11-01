package ai.lily.app.memory

/**
 * Checks the citation markers in a model's answer against the excerpts that were really given to it.
 *
 * A marker is one or more bracketed numbers in a row, such as `[2]`, `[1][3]` or `[1, 3]`, that does not directly follow a word
 * character or another `]`, so code like `arr[0]` or `grid[0][1]` is left alone. Numbers that were not among the [available]
 * excerpts are removed, and a marker with none left is removed with the space before it. The numbers that remain are renumbered
 * from 1 in the order they first appear, so the reader never sees a gap.
 */
object CitationResolver {
    /** [text] with checked, renumbered markers. [used] holds the original excerpt numbers in order: new number 1 is `used[0]`. */
    data class Resolved(val text: String, val used: List<Int>)

    private val RUN = Regex("""(?<![\w\]])(?:\[\d+(?:\s*,\s*\d+)*\])+""")
    private val NUMBER = Regex("""\d+""")

    fun resolve(text: String, available: Int): Resolved {
        val newNumber = LinkedHashMap<Int, Int>()
        val out = StringBuilder()
        var last = 0
        for (m in RUN.findAll(text)) {
            out.append(text, last, m.range.first)
            val valid = NUMBER.findAll(m.value)
                .map { it.value.toIntOrNull() ?: -1 }
                .filter { it in 1..available }
                .distinct()
                .toList()
            if (valid.isEmpty()) {
                if (out.endsWith(" ")) out.setLength(out.length - 1)
            } else {
                valid.map { n -> newNumber.getOrPut(n) { newNumber.size + 1 } }.sorted().forEach { out.append('[').append(it).append(']') }
            }
            last = m.range.last + 1
        }
        out.append(text, last, text.length)
        return Resolved(out.toString(), newNumber.keys.toList())
    }
}
