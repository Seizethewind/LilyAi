package com.seizethewind.lilyai.api.providers

import com.seizethewind.lilyai.api.ChatMessage
import com.seizethewind.lilyai.api.ChatProvider
import com.seizethewind.lilyai.api.ServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Groq chat completions (OpenAI-compatible API). The key is read on every call and never logged. */
class GroqProvider(
    private val info: ServiceInfo,
    private val keyProvider: () -> String?,
    private val modelProvider: () -> String
) : ChatProvider {

    override val id: String = info.id
    override val displayName: String = info.name

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun chat(messages: List<ChatMessage>): Result<String> =
        withContext(Dispatchers.IO) {
            val key = keyProvider()
                ?: return@withContext Result.failure<String>(
                    IllegalStateException("Groq key missing. Open Settings and paste your key.")
                )
            try {
                val array = JSONArray()
                messages.forEach {
                    array.put(JSONObject().put("role", it.role).put("content", it.content))
                }
                val body = JSONObject()
                    .put("model", modelProvider())
                    .put("messages", array)
                    .put("temperature", 0.7)
                    .toString()
                val request = Request.Builder()
                    .url(info.baseUrl.trimEnd('/') + "/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Result.failure<String>(IllegalStateException(errorMessage(response.code, text)))
                    } else {
                        val content = JSONObject(text)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        Result.success(content)
                    }
                }
            } catch (e: IOException) {
                Result.failure<String>(IllegalStateException("Network error. Check your connection."))
            } catch (e: JSONException) {
                Result.failure<String>(IllegalStateException("Unexpected reply from Groq."))
            }
        }

    private fun errorMessage(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: JSONException) {
            ""
        }
        return when (code) {
            401 -> "Groq rejected the key. Check it in Settings."
            429 -> "Groq rate limit reached. Try again in a moment."
            else -> if (detail.isNotBlank()) "Groq error $code: $detail" else "Groq error $code."
        }
    }
}
