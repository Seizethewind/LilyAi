package com.seizethewind.lilyai.tools

import com.seizethewind.lilyai.memory.MemoryManager
import com.seizethewind.lilyai.safety.ActionPermissionLevel

class ListMemoriesTool(private val memory: MemoryManager) : Tool {
    override val name = "list_memories"
    override val description = "Lists the things the user asked you to remember."
    override val argumentHelp = "none"
    override val level = ActionPermissionLevel.SAFE
    override val readsPrivateData = true

    override fun describe(args: Map<String, String>) = "read your saved memories"

    override suspend fun run(args: Map<String, String>): ToolResult {
        val items = memory.all().take(20)
        val text = if (items.isEmpty()) {
            "No memories saved."
        } else {
            items.joinToString("\n") { "- " + it.text }
        }
        return ToolResult(true, text)
    }
}

/** Saving a memory changes something, so the user is always asked first. */
class RememberTool(private val memory: MemoryManager) : Tool {
    override val name = "remember"
    override val description = "Saves a note to the user's memory. The user is asked for permission first."
    override val argumentHelp = "text (the note to save)"
    override val level = ActionPermissionLevel.CONFIRM

    override fun describe(args: Map<String, String>) =
        "save this to memory: \"" + args["text"].orEmpty().take(200) + "\""

    override suspend fun run(args: Map<String, String>): ToolResult {
        val text = args["text"].orEmpty().trim()
        if (text.length !in 3..500) {
            return ToolResult(false, "The note must be between 3 and 500 characters.")
        }
        memory.remember(text)
        return ToolResult(true, "Saved.")
    }
}
