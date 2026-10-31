package ai.lily.app.tools

/**
 * Runs commands in external Termux via the RUN_COMMAND intent.
 * Needs: Termux (F-Droid/GitHub build), allow-external-apps=true in ~/.termux/termux.properties,
 * and the com.termux.permission.RUN_COMMAND permission (already in the manifest).
 * TODO: implement the intent + result PendingIntent; add allowlist / confirm-before-run.
 */
class TerminalTool : Tool {
    override val name = "terminal"

    override suspend fun execute(args: Map<String, String>): String {
        TODO("Send args[command] to Termux RUN_COMMAND and return stdout/stderr")
    }
}
