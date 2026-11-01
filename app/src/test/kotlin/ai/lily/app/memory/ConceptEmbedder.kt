package ai.lily.app.memory

/**
 * A stand-in for a real embedding model, for tests. Words belong to concepts and each concept is one axis, so "spaghetti" and
 * "noodles" point the same way without sharing a letter, and unrelated words are at right angles (similarity exactly 0).
 * A text with no known word gets a tiny value on the last axis so it never becomes the all-zero vector.
 */
class ConceptEmbedder(private val tag: String = "fake-model", private val dims: Int = CONCEPTS.size + 1) : Embedder {
    val calls = ArrayList<List<String>>()
    var failWith: String? = null
    var answerTag: String? = null

    override suspend fun embed(texts: List<String>, tag: String?): EmbeddingBatch {
        failWith?.let { throw EmbeddingException(it) }
        if (tag != null && tag != this.tag) throw EmbeddingException("no model $tag")
        calls += texts
        return EmbeddingBatch(answerTag ?: this.tag, texts.map { vector(it) })
    }

    private fun vector(text: String): FloatArray {
        val v = FloatArray(dims)
        val words = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        for (w in words) CONCEPTS.forEachIndexed { axis, group -> if (w in group) v[axis] += 1f }
        if (v.all { it == 0f }) v[dims - 1] = 0.001f
        return v
    }

    companion object {
        val CONCEPTS = listOf(
            setOf("pasta", "spaghetti", "noodles", "penne"),
            setOf("tomato", "tomatoes", "garden", "plants", "sun"),
            setOf("fox", "dog", "zoo", "animals"),
        )
    }
}
