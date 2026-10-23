package com.seizethewind.lilyai.tools

import com.seizethewind.lilyai.safety.ActionPermissionLevel
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone

class GetTimeTool : Tool {
    override val name = "get_time"
    override val description = "Gets the current date and time."
    override val argumentHelp = "none"
    override val level = ActionPermissionLevel.SAFE

    override fun describe(args: Map<String, String>) = "read the current time"

    override suspend fun run(args: Map<String, String>): ToolResult {
        val format = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL)
        return ToolResult(true, format.format(Date()) + " (" + TimeZone.getDefault().id + ")")
    }
}
