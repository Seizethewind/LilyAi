package com.seizethewind.lilyai.tools

import com.seizethewind.lilyai.safety.ActionPermissionLevel
import org.json.JSONException
import org.json.JSONObject

/** Something the assistant can do. Every tool declares how much trust it needs. */
interface Tool {
    val name: String
    val description: String
    val argumentHelp: String
    val level: ActionPermissionLevel

    /** True if the output contains private data that must not be sent to the cloud model unless allowed. */
    val readsPrivateData: Boolean get() = false

    /** Plain-language description shown in the approval dialog, e.g. "save this to memory: ...". */
    fun describe(args: Map<String, String>): String

    suspend fun run(args: Map<String, String>): ToolResult
}

class ToolRegistry(tools: List<Tool>) {

    private val byName = tools.associateBy { it.name }
    private val ordered = tools

    fun get(name: String): Tool? = byName[name]

    fun isEmpty(): Boolean = ordered.isEmpty()

    /** Instructions that teach the model how to ask for a tool. */
    fun promptSection(): String {
        val sb = StringBuilder()
        sb.append("You can use tools. Only use a tool when the user's request really needs it. ")
        sb.append("To use a tool, reply with exactly one line and nothing else, in this form:\n")
        sb.append("""TOOL: {"name": "tool_name", "args": {"arg": "value"}}""").append('\n')
        sb.append("After you get the tool result, answer the user normally.\nTools:\n")
        for (tool in ordered) {
            sb.append("- ").append(tool.name).append(": ").append(tool.description)
                .append(" Arguments: ").append(tool.argumentHelp).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

/** Reads a reply of the form: TOOL: {"name": "...", "args": {...}} */
object ToolCallParser {

    fun parse(reply: String): ToolRequest? {
        val text = reply.trim()
        if (!text.startsWith("TOOL:")) return null
        val body = text.removePrefix("TOOL:")
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val json = JSONObject(body.substring(start, end + 1))
            val name = json.optString("name")
            if (name.isBlank()) {
                null
            } else {
                val argsJson = json.optJSONObject("args")
                val args = mutableMapOf<String, String>()
                if (argsJson != null) {
                    for (key in argsJson.keys()) args[key] = argsJson.optString(key)
                }
                ToolRequest(name, args)
            }
        } catch (e: JSONException) {
            null
        }
    }
}
