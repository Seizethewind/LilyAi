package ai.lily.app.lib.editor

private const val NEG = Int.MIN_VALUE / 2

private fun isSeparator(c: Char): Boolean = c == '/' || c == '.' || c == '_' || c == '-' || c == ' ' || c == ':'

/** Extra points for a letter that starts the text, a word, or a camelCase hump. */
private fun boundaryBonus(text: String, j: Int): Int {
    if (j == 0) return 8
    val p = text[j - 1]
    val c = text[j]
    return when {
        isSeparator(p) -> 6
        p.isLowerCase() && c.isUpperCase() -> 5
        else -> 0
    }
}

/**
 * How well [query] matches [text] when its letters must appear in order but need not touch ("mnact" finds "MainActivity").
 * Case is ignored and spaces in the query are skipped. Returns null if it does not match. Higher is better:
 * letters that touch, and letters that start a word, score more.
 */
fun fuzzyScore(query: String, text: String): Int? {
    val q = query.filter { !it.isWhitespace() }.lowercase()
    if (q.isEmpty()) return 0
    val m = q.length
    val n = text.length
    if (m > n) return null
    var prev = IntArray(n) { NEG }
    var cur = IntArray(n) { NEG }
    for (i in 0 until m) {
        cur.fill(NEG)
        // Best score of the previous letter sitting 4 or more places back (every longer gap costs the same).
        var far = NEG
        for (j in i until n) {
            if (i > 0 && j - 4 >= 0) far = maxOf(far, prev[j - 4])
            if (text[j].lowercaseChar() != q[i]) continue
            val base = 1 + boundaryBonus(text, j)
            if (i == 0) {
                cur[j] = base - minOf(j, 5)
                continue
            }
            var best = NEG
            if (far > NEG) best = far - 3
            if (j - 3 >= 0 && prev[j - 3] > NEG) best = maxOf(best, prev[j - 3] - 2)
            if (j - 2 >= 0 && prev[j - 2] > NEG) best = maxOf(best, prev[j - 2] - 1)
            if (j - 1 >= 0 && prev[j - 1] > NEG) best = maxOf(best, prev[j - 1] + 5)
            if (best > NEG) cur[j] = best + base
        }
        val swap = prev
        prev = cur
        cur = swap
    }
    var result = NEG
    for (j in 0 until n) result = maxOf(result, prev[j])
    return if (result > NEG) result else null
}

/** Like [fuzzyScore] for a file path: a match in the file name counts for more than one that only fits the folders. */
fun fuzzyPathScore(query: String, path: String): Int? {
    val inName = fuzzyScore(query, path.substringAfterLast('/'))?.plus(40)
    val inPath = fuzzyScore(query, path)
    return when {
        inName == null -> inPath
        inPath == null -> inName
        else -> maxOf(inName, inPath)
    }
}

/**
 * The items that match, best first. Items with the same score keep their original order.
 * An empty [query] returns the first [limit] items as they are.
 */
fun <T> fuzzyRank(query: String, items: List<T>, limit: Int = 50, scorer: (T) -> Int?): List<T> {
    if (query.isBlank()) return items.take(limit)
    return items
        .mapNotNull { item -> scorer(item)?.let { score -> item to score } }
        .sortedByDescending { it.second }
        .take(limit)
        .map { it.first }
}
