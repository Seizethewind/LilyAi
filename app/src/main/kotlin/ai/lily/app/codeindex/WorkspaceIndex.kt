package ai.lily.app.codeindex

import kotlin.coroutines.cancellation.CancellationException

/** A file that looks relevant to a request. [lines] is where its best match is; [score] only orders files against each other. */
data class RelatedFile(val path: String, val score: Double, val lines: IntRange)

/**
 * Finds the files in a project that a request is probably about, so a worker can look beyond the open tabs.
 *
 * Each call first brings the index up to date ([CodeIndex.sync]), which is cheap when nothing changed. If syncing fails the index
 * is used as it was, and if it is empty the answer is empty: this is a helper, and it must never stop an edit.
 */
class WorkspaceIndex(private val files: CodeFiles, val index: CodeIndex = CodeIndex()) {

    /**
     * Up to [maxFiles] files for [request], best first, never one of [exclude] (the files already shown). A file scores its best
     * chunk plus 0.3 of its second best, so a file with two matches beats one with a single equal match. Files far behind the leader
     * (under 35% of its score) are dropped, and for a question with three or more terms a file whose best chunk matches only one
     * of them is dropped too.
     */
    suspend fun relatedTo(request: String, exclude: Set<String> = emptySet(), maxFiles: Int = DEFAULT_MAX_FILES): List<RelatedFile> {
        try {
            index.sync(files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Use the index as it is.
        }
        val termCount = CodeTokenizer.queryTerms(request).size
        val hits = index.search(request, SEARCH_CHUNKS, exclude)
        val byFile = LinkedHashMap<String, MutableList<CodeHit>>()
        for (h in hits) byFile.getOrPut(h.path) { ArrayList() }.add(h)

        val ranked = byFile.values
            .filter { list -> termCount < 3 || list.first().matched >= 2 }
            .map { list ->
                val best = list.first()
                RelatedFile(best.path, best.score + SECOND_WEIGHT * (list.getOrNull(1)?.score ?: 0.0), best.startLine..best.endLine)
            }
            .sortedWith(compareByDescending<RelatedFile> { it.score }.thenBy { it.path })
        val top = ranked.firstOrNull()?.score ?: return emptyList()
        return ranked.filter { it.score >= top * FLOOR }.take(maxFiles.coerceAtLeast(0))
    }

    companion object {
        const val DEFAULT_MAX_FILES = 4
        private const val SEARCH_CHUNKS = 40
        private const val SECOND_WEIGHT = 0.3
        private const val FLOOR = 0.35
    }
}
