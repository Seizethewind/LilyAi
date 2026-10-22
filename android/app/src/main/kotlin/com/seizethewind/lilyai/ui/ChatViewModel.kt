package com.seizethewind.lilyai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seizethewind.lilyai.api.ChatMessage
import com.seizethewind.lilyai.core.Agent
import com.seizethewind.lilyai.core.AgentState
import kotlinx.coroutines.launch

/** role is "user", "assistant" or "error". Errors are shown but never sent back to the model. */
data class UiMessage(val role: String, val text: String)

class ChatViewModel(private val agent: Agent) : ViewModel() {

    var messages by mutableStateOf(listOf<UiMessage>())
        private set

    var state by mutableStateOf(AgentState.IDLE)
        private set

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || state == AgentState.THINKING) return
        messages = messages + UiMessage("user", trimmed)
        state = AgentState.THINKING
        viewModelScope.launch {
            val history = messages
                .filter { it.role != "error" }
                .map { ChatMessage(it.role, it.text) }
            val result = agent.respond(history)
            val reply = result.fold(
                onSuccess = { UiMessage("assistant", it) },
                onFailure = { UiMessage("error", it.message ?: "Something went wrong.") }
            )
            messages = messages + reply
            state = AgentState.IDLE
        }
    }
}
