package ai.lily.app.codeindex

import ai.lily.app.memory.Chunker
import ai.lily.app.memory.KnowledgeText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ln

/** A stretch of a file that matched. Lines are numbered from 1. [matched] is how many different query terms it contains. */
data class CodeHit(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val score: Double,
    val matched: Int,
)

/** What a [CodeIndex.sync] did. [skipped] counts files not indexed (empty, binary, minified, or over capacity). */
data class SyncResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val unchanged: Int,
    val skipped: Int,
    val truncated: Boolean,
)

data class CodeIndexStats(val files: Int, val chunks: Int)

/**
 * A search index over a project's files, kept in memory.
 *
 * Files are cut into chunks of about 200 tokens with a 10% overlap (the knowledge [Chunker]), each with its line range. The file's
 * path counts as words too, so asking about `WorkspaceOps` finds that file. Chunks are ranked with BM25 on the terms from
 * [CodeTokenizer].
 *
 * **Staying current.** [sync] compares each file's stamp (size and modified time) with the one it indexed. Only files whose
 * stamp changed are read again, and a file whose text turned out to be the same is not cut again. A saved file, a file changed by
 * the sandbox or git, a new file and a deleted file are all handled the same way. [update] is a shortcut for one file whose text
 * is already at hand.
 *
 * Nothing is stored on disk: the index is rebuilt from the files when a session starts. It holds at most [maxChunks] chunks;
 * files that do not fit are left out and [SyncResult.truncated] says so. All calls are safe from several coroutines.
 */
class CodeIndex(
    private val targetTokens: Int = CODE_CHUNK_TOKENS,
    private val overlapPercent: Int = CODE_OVERLAP_PERCENT,
    private val maxChunks: Int = MAX_CHUNKS,
) {
    // Chunks are compared by identity on purpose: two chunks with the same text are still different chunks.
    private class IndexedChunk(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val text: String,
        val tf: Map<String, Int>,
        val length: Int,
    )

    private class IndexedFile(val stamp: FileStamp, val hash: String, val chunks: List<IndexedChunk>)

    private val mutex = Mutex()
    private val files = HashMap<String, IndexedFile>()
    private val postings = HashMap<String, MutableSet<IndexedChunk>>()
    private val leftOut = HashMap<String, FileStamp>()
    private var chunkCount = 0
    private var totalLength = 0L

    suspend fun stats(): CodeIndexStats = mutex.withLock { CodeIndexStats(files.size, chunkCount) }

    /** Brings the index in line with [source]: reads only what changed since the last sync. */
    suspend fun sync(source: CodeFiles): SyncResult = mutex.withLock {
        val current = source.stamps()
        var added = 0
        var updated = 0
        var removed = 0
        var unchanged = 0
        var skipped = 0
        var truncated = current.truncated

        for (path in files.keys.toList()) {
            if (path !in current.files) {
                removeFile(path)
                removed++
            }
        }
        leftOut.keys.retainAll(current.files.keys)

        for ((path, stamp) in current.files) {
            val old = files[path]
            if (old != null && old.stamp == stamp) {
                unchanged++
                continue
            }
            if (old == null && leftOut[path] == stamp) {
                skipped++
                continue
            }
            val text = source.read(path)
            if (text == null || text.isBlank() || CodeFilter.looksGenerated(text)) {
                if (old != null) {
                    removeFile(path)
                    removed++
                }
                leftOut[path] = stamp
                skipped++
                continue
            }
            val hash = KnowledgeText.hash(text)
            if (old != null && old.hash == hash) {
                files[path] = IndexedFile(stamp, hash, old.chunks)
                unchanged++
                continue
            }
            if (old != null) removeFile(path)
            if (addFile(path, stamp, hash, text)) {
                leftOut.remove(path)
                if (old == null) added++ else updated++
            } else {
                truncated = true
                leftOut[path] = stamp
                skipped++
            }
        }
        SyncResult(added, updated, removed, unchanged, skipped, truncated)
    }

    /**
     * Replaces one file's chunks with [text], or drops the file when [text] is null, blank, binary or minified. The file is marked
     * for a check at the next [sync], which finds its text unchanged and only refreshes the stamp.
     */
    suspend fun update(path: String, text: String?) = mutex.withLock {
        val old = files[path]
        if (text == null || text.isBlank() || CodeFilter.looksGenerated(text)) {
            if (old != null) removeFile(path)
            return@withLock
        }
        val hash = KnowledgeText.hash(text)
        if (old != null && old.hash == hash) return@withLock
        if (old != null) removeFile(path)
        addFile(path, FileStamp(-1, -1), hash, text)
        leftOut.remove(path)
    }

    /**
     * The best chunks for [query], best first, at most [limit] (up to 100), skipping files in [exclude]. Empty when the query has
     * no usable terms or nothing matches.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT, exclude: Set<String> = emptySet()): List<CodeHit> = mutex.withLock {
        val terms = CodeTokenizer.queryTerms(query)
        if (terms.isEmpty() || chunkCount == 0) return@withLock emptyList()
        val avgLength = maxOf(totalLength.toDouble() / chunkCount, 1.0)
        val scores = HashMap<IndexedChunk, Double>()
        val matched = HashMap<IndexedChunk, Int>()
        for (t in terms) {
            val hits = postings[t] ?: continue
            val idf = ln(1.0 + (chunkCount - hits.size + 0.5) / (hits.size + 0.5))
            for (c in hits) {
                if (c.path in exclude) continue
                val tf = c.tf.getValue(t)
                val norm = tf + K1 * (1 - B + B * c.length / avgLength)
                scores[c] = (scores[c] ?: 0.0) + idf * tf * (K1 + 1) / norm
                matched[c] = (matched[c] ?: 0) + 1
            }
        }
        scores.entries
            .sortedWith(compareByDescending<Map.Entry<IndexedChunk, Double>> { it.value }.thenBy { it.key.path }.thenBy { it.key.startLine })
            .take(limit.coerceIn(1, MAX_LIMIT))
            .map { (c, score) -> CodeHit(c.path, c.startLine, c.endLine, c.text, score, matched[c] ?: 0) }
    }

    private fun addFile(path: String, stamp: FileStamp, hash: String, text: String): Boolean {
        val pieces = Chunker.split(text, targetTokens, overlapPercent)
        if (chunkCount + pieces.size > maxChunks) return false
        val pathTerms = CodeTokenizer.terms(path).distinct()
        val chunks = pieces.map { p ->
            val terms = CodeTokenizer.terms(p.text)
            val tf = HashMap<String, Int>()
            for (t in terms) tf[t] = (tf[t] ?: 0) + 1
            for (t in pathTerms) tf[t] = (tf[t] ?: 0) + 1
            IndexedChunk(path, p.startLine, p.endLine, p.text, tf, terms.size)
        }
        for (c in chunks) {
            for (t in c.tf.keys) postings.getOrPut(t) { HashSet() }.add(c)
            chunkCount++
            totalLength += c.length
        }
        files[path] = IndexedFile(stamp, hash, chunks)
        return true
    }

    private fun removeFile(path: String) {
        val old = files.remove(path) ?: return
        for (c in old.chunks) {
            for (t in c.tf.keys) {
                val set = postings[t] ?: continue
                set.remove(c)
                if (set.isEmpty()) postings.remove(t)
            }
            chunkCount--
            totalLength -= c.length
        }
    }

    companion object {
        const val CODE_CHUNK_TOKENS = 200
        const val CODE_OVERLAP_PERCENT = 10
        const val MAX_CHUNKS = 20_000
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 100
        private const val K1 = 1.2
        private const val B = 0.75
    }
}
