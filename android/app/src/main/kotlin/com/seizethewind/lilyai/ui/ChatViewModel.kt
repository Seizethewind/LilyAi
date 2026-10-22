package com.seizethewind.lilyai.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seizethewind.lilyai.api.ChatMessage
import com.seizethewind.lilyai.core.Agent
import com.seizethewind.lilyai.core.AgentState
import com.seizethewind.lilyai.core.ResponseListener
import com.seizethewind.lilyai.core.StoppedException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** role is "user", "assistant" or "error". Errors are shown but never sent back to the model. */
data class UiMessage(val role: String, val text: String, val engine: String? = null)

class ChatViewModel(private val agent: Agent) : ViewModel() {

    var messages by mutableStateOf(listOf<UiMessage>())
        private set

    var state by mutableStateOf(AgentState.IDLE)
        private set

    private var job: Job? = null

    @Volatile
    private var localActive = false

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || state == AgentState.THINKING) return
        val userMessage = UiMessage("user", trimmed)
        val history = (messages + userMessage)
            .filter { it.role != "error" }
            .map { ChatMessage(it.role, it.text) }
        messages = messages + userMessage
        state = AgentState.THINKING
        localActive = false

        job = viewModelScope.launch {
            var placeholder = false
            var engine: String? = null
            val sb = StringBuilder()

            fun publish(message: UiMessage) {
                messages = if (placeholder) {
                    messages.dropLast(1) + message
                } else {
                    placeholder = true
                    messages + message
                }
            }

            val listener = object : ResponseListener {
                override fun onEngineChosen(name: String) {
                    engine = name
                    localActive = name == Agent.LOCAL_NAME
                }

                override fun onToken(text: String) {
                    sb.append(text)
                    publish(UiMessage("assistant", sb.toString(), engine))
                }
            }

            try {
                val result = agent.respond(history, listener)
                result.fold(
                    onSuccess = { publish(UiMessage("assistant", it, engine)) },
                    onFailure = { err ->
                        if (err !is StoppedException) {
                            messages = messages + UiMessage("error", err.message ?: "Something went wrong.")
                        }
                    }
                )
            } finally {
                state = AgentState.IDLE
            }
        }
    }

    /** Stops the local model right away; a cloud request is simply abandoned. */
    fun stop() {
        agent.stop()
        if (!localActive) job?.cancel()
    }
}
