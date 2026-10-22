package com.seizethewind.lilyai.model

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ModelState {
    data class NotDownloaded(val partialBytes: Long) : ModelState
    data class Downloading(val downloaded: Long, val total: Long) : ModelState
    object Ready : ModelState
    data class Error(val message: String) : ModelState
}

/**
 * Downloads the Qwen3-4B model inside the app after install. The file is never bundled in the APK.
 * Downloads resume from where they stopped (HTTP Range), and the finished file is checked
 * for the expected size and the GGUF header before it is used.
 */
class ModelDownloadManager(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "models").apply { mkdirs() }
    private val finalFile = File(dir, FILE_NAME)
    private val partFile = File(dir, "$FILE_NAME.part")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<ModelState>(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun modelFile(): File = finalFile

    fun isReady(): Boolean = finalFile.exists() && finalFile.length() >= MIN_VALID_BYTES

    @Synchronized
    fun start() {
        if (job?.isActive == true || isReady()) return
        job = scope.launch { download() }
    }

    /** Pauses the download. The partial file is kept so it can resume later. */
    fun cancel() {
        job?.cancel()
    }

    suspend fun delete() {
        job?.cancelAndJoin()
        finalFile.delete()
        partFile.delete()
        _state.value = ModelState.NotDownloaded(0)
    }

    private fun initialState(): ModelState =
        if (isReady()) ModelState.Ready
        else ModelState.NotDownloaded(if (partFile.exists()) partFile.length() else 0L)

    private suspend fun download() {
        var existing = if (partFile.exists()) partFile.length() else 0L
        _state.value = ModelState.Downloading(existing, 0)
        try {
            withContext(Dispatchers.IO) {
                val builder = Request.Builder().url(MODEL_URL)
                if (existing > 0) builder.header("Range", "bytes=$existing-")
                client.newCall(builder.build()).execute().use { response ->
                    if (response.code == 416) {
                        partFile.delete()
                        throw IOException("The partial download was out of date. Tap download to start again.")
                    }
                    if (!response.isSuccessful) {
                        throw IOException("The server returned error ${response.code}.")
                    }
                    val body = response.body ?: throw IOException("Empty response from server.")
                    val append = response.code == 206
                    val total: Long = if (append) {
                        parseTotal(response.header("Content-Range")) ?: (existing + body.contentLength())
                    } else {
                        existing = 0
                        body.contentLength()
                    }
                    if (total <= 0) throw IOException("Could not determine the file size.")

                    val needed = total - existing
                    if (dir.usableSpace < needed + SPACE_MARGIN) {
                        throw IOException("Not enough free space. About ${needed / MB} MB is needed.")
                    }

                    var downloaded = existing
                    var lastUpdate = 0L
                    FileOutputStream(partFile, append).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        val input = body.byteStream()
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            downloaded += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastUpdate > 400) {
                                lastUpdate = now
                                _state.value = ModelState.Downloading(downloaded, total)
                            }
                        }
                    }

                    if (partFile.length() != total) {
                        throw IOException("The download stopped early. Tap resume to continue.")
                    }
                    if (!hasGgufHeader(partFile)) {
                        partFile.delete()
                        throw IOException("The downloaded file is not a valid model. Tap download to try again.")
                    }
                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        throw IOException("Could not save the model file.")
                    }
                }
            }
            _state.value = ModelState.Ready
        } catch (e: CancellationException) {
            _state.value = ModelState.NotDownloaded(if (partFile.exists()) partFile.length() else 0L)
            throw e
        } catch (e: Exception) {
            _state.value = ModelState.Error(e.message ?: "The download failed.")
        }
    }

    private fun parseTotal(contentRange: String?): Long? =
        contentRange?.substringAfter('/', "")?.trim()?.toLongOrNull()

    private fun hasGgufHeader(file: File): Boolean =
        try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
            }
        } catch (e: IOException) {
            false
        }

    companion object {
        const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf"
        const val FILE_NAME = "Qwen3-4B-Q4_K_M.gguf"
        private const val MB = 1024L * 1024L
        private const val MIN_VALID_BYTES = 1_000_000_000L
        private const val SPACE_MARGIN = 200L * MB
    }
}
