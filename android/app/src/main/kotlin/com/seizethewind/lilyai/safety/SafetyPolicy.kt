package com.seizethewind.lilyai.safety

import com.seizethewind.lilyai.tools.Tool

sealed interface Decision {
    object Allow : Decision
    data class Ask(val message: String) : Decision
    data class Deny(val reason: String) : Decision
}

/**
 * Decides what happens before any tool runs. It also holds the emergency stop: once halted,
 * nothing runs until the next user request starts.
 */
class SafetyPolicy {

    @Volatile
    var halted: Boolean = false
        private set

    private var calls = 0

    /** Called at the start of every user request. */
    @Synchronized
    fun newTask() {
        halted = false
        calls = 0
    }

    /** The Stop button: blocks every further action for this request. */
    fun halt() {
        halted = true
    }

    @Synchronized
    fun decide(tool: Tool, args: Map<String, String>): Decision {
        if (halted) return Decision.Deny("Stopped by the user.")
        calls++
        if (calls > MAX_CALLS_PER_TASK) return Decision.Deny("Too many tool calls for one request.")
        return when (tool.level) {
            ActionPermissionLevel.SAFE -> Decision.Allow
            ActionPermissionLevel.CONFIRM -> Decision.Ask("LilyAi wants to ${tool.describe(args)}.")
            ActionPermissionLevel.BLOCKED -> Decision.Deny("This action is not allowed.")
        }
    }

    companion object {
        private const val MAX_CALLS_PER_TASK = 5
    }
}
