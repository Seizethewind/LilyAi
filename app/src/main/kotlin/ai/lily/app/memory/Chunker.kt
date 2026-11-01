package ai.lily.app.memory

/** A piece of a source's text. Lines are numbered from 1 in the original text; [ordinal] counts from 0 within the source. */
data class Chunk(val ordinal: Int, val startLine: Int, val endLine: Int, val text: String, val tokens: Int)

object KnowledgeTokens {
    /** A cheap estimate of about four characters to a token. Good enough to size chunks; it is not any model's real count. */
    fun estimate(text: String): Int = (text.length + 3) / 4
}

/**
 * Cuts a source's text into chunks of about [TARGET_TOKENS] tokens that overlap by [OVERLAP_PERCENT] percent.
 *
 * - Chunks are made of whole lines, so each one has a line range that an editor can open. Lines are never reordered.
 * - A line that is too long for one chunk is cut at a space (or, with no space, at a hard limit) and its pieces share that
 *   line number.
 * - Neighbouring chunks share their last few lines, up to the overlap. If the last line is longer than the overlap there is no
 *   overlap at that point, so the text always moves forward.
 * - Chunks that are only blank lines are dropped.
 *
 * [VERSION] goes into [signature]. Change the way text is cut and the version must go up, so existing chunks are rebuilt.
 */
object Chunker {
    const val VERSION = 1
    const val TARGET_TOKENS = 400
    const val OVERLAP_PERCENT = 15
    private const val MIN_TARGET_TOKENS = 10
    private val BREAKS = charArrayOf(' ', '\t')

    fun signature(targetTokens: Int = TARGET_TOKENS, overlapPercent: Int = OVERLAP_PERCENT) = "v$VERSION/$targetTokens/$overlapPercent"

    private class Piece(val line: Int, val text: String, val tokens: Int)

    fun split(content: String, targetTokens: Int = TARGET_TOKENS, overlapPercent: Int = OVERLAP_PERCENT): List<Chunk> {
        require(targetTokens >= MIN_TARGET_TOKENS) { "A chunk must be at least $MIN_TARGET_TOKENS tokens." }
        require(overlapPercent in 0..50) { "The overlap must be between 0 and 50 percent." }
        val pieces = pieces(content, targetTokens)
        val overlapTokens = targetTokens * overlapPercent / 100
        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (start < pieces.size) {
            var end = start
            var used = pieces[start].tokens
            while (end + 1 < pieces.size && used + pieces[end + 1].tokens <= targetTokens) {
                end++
                used += pieces[end].tokens
            }
            val text = (start..end).joinToString("\n") { pieces[it].text }
            if (text.isNotBlank()) {
                chunks += Chunk(chunks.size, pieces[start].line, pieces[end].line, text, KnowledgeTokens.estimate(text))
            }
            if (end == pieces.size - 1) break
            // The next chunk starts a few pieces back, as far as the overlap allows, but always after this chunk's start.
            var next = end + 1
            var shared = 0
            while (next - 1 > start && shared + pieces[next - 1].tokens <= overlapTokens) {
                next--
                shared += pieces[next].tokens
            }
            start = next
        }
        return chunks
    }

    private fun pieces(content: String, targetTokens: Int): List<Piece> {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val count = if (lines.last().isEmpty()) lines.size - 1 else lines.size
        val maxChars = targetTokens * 4 - 1
        val out = ArrayList<Piece>()
        for (i in 0 until count) {
            for (part in cut(lines[i], maxChars)) {
                out += Piece(i + 1, part, KnowledgeTokens.estimate(part + "\n"))
            }
        }
        return out
    }

    /** Pieces of at most [maxChars] characters that, put back to back, give the line again. */
    private fun cut(line: String, maxChars: Int): List<String> {
        if (line.length <= maxChars) return listOf(line)
        val parts = ArrayList<String>()
        var from = 0
        while (from < line.length) {
            var to = minOf(from + maxChars, line.length)
            if (to < line.length) {
                val space = line.lastIndexOfAny(BREAKS, to - 1)
                if (space > from + maxChars / 2) {
                    to = space + 1
                } else if (Character.isHighSurrogate(line[to - 1])) {
                    to--
                }
            }
            parts += line.substring(from, to)
            from = to
        }
        return parts
    }
}
