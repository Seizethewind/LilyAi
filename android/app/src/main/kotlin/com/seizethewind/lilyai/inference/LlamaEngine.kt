package com.seizethewind.lilyai.inference

import com.seizethewind.lilyai.api.ChatMessage
import com.seizethewind.lilyai.core.StoppedException
import com.seizethewind.lilyai.model.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs Qwen3-4B on the phone through llama.cpp. Loads the model on first use. */
class LlamaEngine(private val modelManager: ModelDownloadManager) {

    private val mutex = Mutex()

    @Volatile
    private var handle = 0L

    @Volatile
    private var stopRequested = false

    /** True when the native library loaded and the model file is downloaded. */
    fun isReady(): Boolean = LlamaNative.available && modelManager.isReady()

    fun cancel() {
        stopRequested = true
        val h = handle
        if (h != 0L) LlamaNative.cancel(h)
    }

    /** Frees the model from memory. Call before deleting the model file. */
    suspend fun unload() {
        mutex.withLock {
            val h = handle
            handle = 0L
            if (h != 0L) LlamaNative.free(h)
        }
    }

    suspend fun chat(messages: List<ChatMessage>, onToken: (String) -> Unit): Result<String> =
        withContext(Dispatchers.Default) {
            if (!LlamaNative.available) {
                return@withContext Result.failure<String>(
                    IllegalStateException("The local engine can't run on this device (it needs a 64-bit ARM phone).")
                )
            }
            if (!modelManager.isReady()) {
                return@withContext Result.failure<String>(
                    IllegalStateException("The local model is not downloaded yet. Open Settings to download it.")
                )
            }
            mutex.withLock {
                try {
                    if (handle == 0L) {
                        val h = LlamaNative.load(
                            modelManager.modelFile().absolutePath,
                            N_CTX,
                            threadCount()
                        )
                        if (h == 0L) {
                            return@withLock Result.failure<String>(
                                IllegalStateException(
                                    "Could not load the local model. Delete it in Settings and download it again."
                                )
                            )
                        }
                        handle = h
                    }
                    stopRequested = false
                    val prompt = buildPrompt(messages)
                    val out = StringBuilder()
                    val rc = LlamaNative.generate(
                        handle,
                        prompt.toByteArray(Charsets.UTF_8),
                        MAX_NEW_TOKENS,
                        0.7f,
                        0.9f,
                        40,
                        TokenCallback { bytes ->
                            val piece = String(bytes, Charsets.UTF_8)
                            out.append(piece)
                            onToken(piece)
                            !stopRequested
                        }
                    )
                    when {
                        rc == -2 -> Result.failure<String>(
                            IllegalStateException("This conversation is too long for the local model. Start a new chat.")
                        )
                        rc < 0 -> Result.failure<String>(
                            IllegalStateException("Local generation failed (code $rc).")
                        )
                        stopRequested -> Result.failure<String>(StoppedException())
                        else -> Result.success(out.toString().trim())
                    }
                } catch (e: UnsatisfiedLinkError) {
                    Result.failure<String>(IllegalStateException("The local engine failed to start."))
                }
            }
        }

    /** Qwen3 uses the ChatML format. The empty think block turns off Qwen3's thinking mode. */
    private fun buildPrompt(messages: List<ChatMessage>): String {
        val system = messages.firstOrNull { it.role == "system" }?.content.orEmpty()
        val rest = messages.filter { it.role != "system" }.toMutableList()
        val budgetChars = (N_CTX - MAX_NEW_TOKENS - 64) * CHARS_PER_TOKEN
        fun size() = system.length + rest.sumOf { it.content.length + 16 }
        while (rest.size > 1 && size() > budgetChars) rest.removeAt(0)

        val sb = StringBuilder()
        if (system.isNotEmpty()) {
            sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        }
        for (m in rest) {
            sb.append("<|im_start|>").append(m.role).append('\n')
                .append(m.content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        return sb.toString()
    }

    private fun threadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    companion object {
        private const val N_CTX = 2048
        private const val MAX_NEW_TOKENS = 512
        private const val CHARS_PER_TOKEN = 3

        /** False on emulators and 32-bit phones where the native library can't load. */
        val isSupported: Boolean get() = LlamaNative.available
    }
}
