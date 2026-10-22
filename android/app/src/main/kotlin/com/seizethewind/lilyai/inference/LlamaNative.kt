package com.seizethewind.lilyai.inference

/** Receives generated text as UTF-8 bytes. Return false to stop generating. */
fun interface TokenCallback {
    fun onToken(bytes: ByteArray): Boolean
}

/** JNI bridge to llama.cpp (liblilyai_llama.so). */
internal object LlamaNative {

    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("lilyai_llama")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    external fun load(path: String, nCtx: Int, nThreads: Int): Long

    external fun generate(
        handle: Long,
        prompt: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        callback: TokenCallback
    ): Int

    external fun cancel(handle: Long)

    external fun free(handle: Long)
}
