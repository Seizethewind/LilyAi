package com.seizethewind.lilyai.tools

import com.seizethewind.lilyai.safety.ConfirmationManager
import com.seizethewind.lilyai.safety.Decision
import com.seizethewind.lilyai.safety.SafetyPolicy
import kotlinx.coroutines.CancellationException

/** Runs a tool only after the safety policy (and, if needed, the user) allows it. */
class ToolExecutor(
    private val policy: SafetyPolicy,
    private val confirmations: ConfirmationManager
) {

    suspend fun execute(tool: Tool, args: Map<String, String>): ToolResult =
        when (val decision = policy.decide(tool, args)) {
            Decision.Allow -> safeRun(tool, args)
            is Decision.Ask -> {
                if (!confirmations.ask(decision.message)) {
                    ToolResult(false, "The user declined.")
                } else if (policy.halted) {
                    ToolResult(false, "Stopped by the user.")
                } else {
                    safeRun(tool, args)
                }
            }
            is Decision.Deny -> ToolResult(false, decision.reason)
        }

    private suspend fun safeRun(tool: Tool, args: Map<String, String>): ToolResult =
        try {
            tool.run(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "The tool failed: ${e.message}")
        }
}
