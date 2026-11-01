package ai.lily.app.memory

/**
 * Vectors for some texts, all made by the model named [tag]. [tag] is the model id the app pinned (plus the vector size when one
 * was requested), never `auto`, so every vector in the library can be compared with every other.
 */
class EmbeddingBatch(val tag: String, val vectors: List<FloatArray>)

/** An embedding request failed in a way worth telling the person: every endpoint was tried, or an answer made no sense. */
class EmbeddingException(message: String) : Exception(message)

/** Turns texts into vectors. The real one talks to the routers; tests use a fake. */
interface Embedder {
    /**
     * One vector per text, in the same order. If [tag] is given, only a model with that tag may answer and the answer carries it;
     * a model that cannot answer is an [EmbeddingException], never a vector from a different model.
     */
    suspend fun embed(texts: List<String>, tag: String? = null): EmbeddingBatch
}
