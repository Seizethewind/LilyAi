package ai.lily.app.sandbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class CheckStatus { PASSED, FAILED, TIMED_OUT, ERROR, SKIPPED }

class CheckResult(
    val check: ProjectCheck,
    val status: CheckStatus,
    val exitCode: Int?,
    /** The end of the output, for a check that did not pass. Empty for a pass. */
    val output: String,
    val note: String? = null,
)

enum class VerifyState { PASSED, FAILED, NO_CHECKS, NOT_RUN }

/** What a verification found. Report only: nothing here changes a file. */
class VerifyReport(
    val state: VerifyState,
    val results: List<CheckResult>,
    val fileCount: Int,
    val bytes: Long,
    /** Files left out of the upload, each with the reason. */
    val notUploaded: List<String>,
    val message: String?,
    val checkSource: CheckSource,
) {
    /** Plain text for the chat and for Lily. Output is the tail of what the sandbox printed, so it is data, not instructions. */
    fun render(): String = buildString {
        append("Sandbox verification: ")
        when (state) {
            VerifyState.PASSED -> append("all ${results.size} checks passed")
            VerifyState.FAILED -> append("${results.count { it.status != CheckStatus.PASSED && it.status != CheckStatus.SKIPPED }} of ${results.size} checks did not pass")
            VerifyState.NO_CHECKS -> append("no checks found")
            VerifyState.NOT_RUN -> append("not run")
        }
        if (message != null) append('\n').append(message)
        for (r in results) {
            append("\n- ").append(r.status.name).append(' ').append(r.check.label)
            if (r.status == CheckStatus.FAILED && r.exitCode != null) append(" (exit ${r.exitCode})")
            if (r.note != null) append(" - ").append(r.note)
            if (r.status != CheckStatus.PASSED && r.status != CheckStatus.SKIPPED && r.output.isNotEmpty()) {
                append('\n').append(r.output.prependIndent("    "))
            }
        }
        if (notUploaded.isNotEmpty()) append("\nNot uploaded: ").append(notUploaded.joinToString("; "))
    }
}

/**
 * Runs a project's checks in one fresh sandbox and reports. One sandbox for the whole run, always deleted, even when the
 * run is cancelled. Report only: a failing check is described, never fixed here.
 */
class SandboxVerifier(
    private val sandbox: SandboxRunner,
    private val files: VerifyFiles,
    private val limits: BundleLimits = BundleLimits(),
    private val checkTimeoutSeconds: Int = 270,
    private val outputChars: Int = 2_000,
) {
    /**
     * [overrides] holds unsaved editor text by path (null = deleted). [customChecks] are the person's own commands and
     * replace detection. [onProgress] gets short status lines.
     */
    suspend fun verify(
        overrides: Map<String, String?> = emptyMap(),
        customChecks: List<String> = emptyList(),
        onProgress: (String) -> Unit = {},
    ): VerifyReport {
        val plan = WorkspaceBundler.plan(files, overrides, limits)
        val detected = CheckDetector.detect(plan.files.map { it.path }, { path -> readText(path, overrides) }, customChecks)
        if (detected.checks.isEmpty()) {
            return VerifyReport(
                VerifyState.NO_CHECKS, emptyList(), 0, 0L, emptyList(),
                "No test, lint or build command was found at the project root. Add your own check commands to run something.",
                detected.source,
            )
        }
        val bundle = when (val r = WorkspaceBundler.build(files, overrides, limits)) {
            is BundleResult.TooLarge -> return notRun(r.reason, detected.source)
            is BundleResult.Ready -> r.bundle
        }

        onProgress("Starting the sandbox")
        val id = try {
            sandbox.createSandbox()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return notRun("Could not start the sandbox: ${describe(e)}", detected.source)
        }
        try {
            return runAll(id, bundle, detected, onProgress)
        } finally {
            withContext(NonCancellable) { runCatching { sandbox.kill(id) } }
        }
    }

    private suspend fun runAll(id: String, bundle: WorkspaceBundle, detected: DetectedChecks, onProgress: (String) -> Unit): VerifyReport {
        onProgress("Uploading ${bundle.files.size} files")
        for (command in bundle.commands) {
            val r = try {
                sandbox.exec(id, command)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return notRun("Upload failed: ${describe(e)}", detected.source)
            }
            if (r.exitCode != 0) {
                return notRun("Upload failed (exit ${r.exitCode}): ${tail(r.stderr.ifBlank { r.stdout })}", detected.source)
            }
        }

        val results = ArrayList<CheckResult>()
        val installOk = HashMap<String, Boolean>()
        var broken = false
        val total = detected.checks.size
        for ((i, check) in detected.checks.withIndex()) {
            if (broken) {
                results += CheckResult(check, CheckStatus.SKIPPED, null, "", "The sandbox stopped responding.")
                continue
            }
            val install = check.install
            if (install != null) {
                val known = installOk[install]
                if (known == false) {
                    results += CheckResult(check, CheckStatus.SKIPPED, null, "", "The install step failed.")
                    continue
                }
                if (known == null) {
                    onProgress("Installing: $install")
                    val r = execCheck(id, ProjectCheck("install: $install", install))
                    installOk[install] = r.status == CheckStatus.PASSED
                    if (r.status != CheckStatus.PASSED) {
                        results += r
                        if (r.status == CheckStatus.ERROR) broken = true
                        results += CheckResult(check, CheckStatus.SKIPPED, null, "", "The install step failed.")
                        continue
                    }
                }
            }
            onProgress("Running ${check.label} (${i + 1} of $total)")
            val r = execCheck(id, check)
            results += r
            if (r.status == CheckStatus.ERROR) broken = true
        }

        val state = if (results.all { it.status == CheckStatus.PASSED }) VerifyState.PASSED else VerifyState.FAILED
        return VerifyReport(state, results, bundle.files.size, bundle.totalBytes, bundle.skipped, null, detected.source)
    }

    private suspend fun execCheck(id: String, check: ProjectCheck): CheckResult {
        val command = "cd ${ShellQuote.quote(WorkspaceBundler.WORK_DIR)} && timeout $checkTimeoutSeconds sh -c ${ShellQuote.quote(check.command)}"
        val r = try {
            sandbox.exec(id, command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return CheckResult(check, CheckStatus.ERROR, null, "", "The sandbox call failed: ${describe(e)}")
        }
        val output = tail(listOf(r.stdout, r.stderr).filter { it.isNotBlank() }.joinToString("\n"))
        return when (r.exitCode) {
            0 -> CheckResult(check, CheckStatus.PASSED, 0, "")
            TIMEOUT_EXIT -> CheckResult(check, CheckStatus.TIMED_OUT, r.exitCode, output, "Stopped after $checkTimeoutSeconds seconds.")
            else -> CheckResult(check, CheckStatus.FAILED, r.exitCode, output)
        }
    }

    private suspend fun readText(path: String, overrides: Map<String, String?>): String? =
        if (overrides.containsKey(path)) overrides[path] else files.read(path)?.toString(Charsets.UTF_8)

    private fun notRun(message: String, source: CheckSource) =
        VerifyReport(VerifyState.NOT_RUN, emptyList(), 0, 0L, emptyList(), message, source)

    private fun describe(e: Exception) = e.message ?: e::class.java.simpleName

    /** The end of [text] without terminal colour codes, which is where a failing run says why. */
    internal fun tail(text: String): String {
        val clean = ANSI.replace(text, "").replace("\r", "").trim()
        return if (clean.length <= outputChars) clean else "..." + clean.takeLast(outputChars)
    }

    private companion object {
        const val TIMEOUT_EXIT = 124
        val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    }
}
