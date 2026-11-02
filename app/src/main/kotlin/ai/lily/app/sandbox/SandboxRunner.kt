package ai.lily.app.sandbox

/** What the verifier needs from a sandbox service. [PandaStackClient] is the real one; tests use a fake. */
interface SandboxRunner {
    suspend fun createSandbox(ttlSeconds: Int = 600): String

    suspend fun exec(sandboxId: String, command: String): ExecResult

    suspend fun kill(sandboxId: String)
}

/** Quotes text as one shell word. */
internal object ShellQuote {
    fun quote(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}
