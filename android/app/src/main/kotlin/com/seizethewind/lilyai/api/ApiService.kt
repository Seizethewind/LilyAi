package com.seizethewind.lilyai.api

data class ChatMessage(val role: String, val content: String)

/** Anything in the API directory: a cloud model, a memory database, and so on. */
interface ApiService {
    val id: String
    val displayName: String
}

/** A service that can answer chat messages. */
interface ChatProvider : ApiService {
    suspend fun chat(messages: List<ChatMessage>): Result<String>
}
