package ai.lily.app.tools

import ai.lily.app.sandbox.PandaStackClient

/** Test-runs a command in a fresh Pandastack sandbox. The command is one string; chain steps with && or bash -c. */
class SandboxTool(private val client: PandaStackClient) : Tool {
    override val name = "sandbox"

    override suspend fun execute(args: Map<String, String>): String {
        val command = args["command"] ?: error("command is required")
        val r = client.runOnce(command)
        val err = if (r.stderr.isNotBlank()) "\n[stderr]\n${r.stderr}" else ""
        return "exit=${r.exitCode}\n${r.stdout}$err"
    }
}
