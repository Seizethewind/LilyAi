package ai.lily.app.sandbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxVerifierTest {
    private val npm = arrayOf(
        "package.json" to """{"scripts":{"test":"jest"}}""",
        "package-lock.json" to "{}",
        "src/a.js" to "x",
    )

    private val npmTwoScripts = arrayOf(
        "package.json" to """{"scripts":{"lint":"eslint .","test":"jest"}}""",
        "package-lock.json" to "{}",
    )

    private fun verify(
        sandbox: FakeSandbox,
        files: VerifyFiles,
        overrides: Map<String, String?> = emptyMap(),
        custom: List<String> = emptyList(),
        limits: BundleLimits = BundleLimits(),
        outputChars: Int = 2_000,
        progress: (String) -> Unit = {},
    ) = runBlocking { SandboxVerifier(sandbox, files, limits, outputChars = outputChars).verify(overrides, custom, progress) }

    @Test
    fun aProjectWithNoChecksNeverStartsASandbox() {
        val sandbox = FakeSandbox()

        val report = verify(sandbox, FakeVerifyFiles("README.md" to "hello"))

        assertEquals(VerifyState.NO_CHECKS, report.state)
        assertTrue(sandbox.created.isEmpty())
        assertTrue(report.render().contains("no checks found"))
    }

    @Test
    fun passingChecksUploadInstallRunAndDeleteTheSandbox() {
        val sandbox = FakeSandbox()

        val report = verify(sandbox, FakeVerifyFiles(*npm))

        assertEquals(VerifyState.PASSED, report.state)
        assertEquals(listOf(CheckStatus.PASSED), report.results.map { it.status })
        assertEquals(listOf("sb1"), sandbox.killed)
        assertTrue(sandbox.commands.first().contains("rm -rf '/tmp/work'"))
        assertTrue(sandbox.commands.any { it.contains("sh -c 'npm ci'") })
        assertEquals("cd '/tmp/work' && timeout 270 sh -c 'CI=true npm run test'", sandbox.commands.last())
        assertTrue(report.render().contains("all 1 checks passed"))
    }

    @Test
    fun aFailingCheckKeepsTheEndOfItsOutputWithoutColourCodes() {
        val sandbox = FakeSandbox()
        sandbox.onExec = { cmd ->
            if (cmd.contains("npm run test")) ExecResult("", "\u001B[31mFAIL\u001B[0m src/a.test.js\nExpected 1", 1) else ExecResult("", "", 0)
        }

        val report = verify(sandbox, FakeVerifyFiles(*npm))
        val result = report.results.single()

        assertEquals(VerifyState.FAILED, report.state)
        assertEquals(CheckStatus.FAILED, result.status)
        assertEquals(1, result.exitCode)
        assertTrue(result.output.contains("FAIL src/a.test.js"))
        assertFalse(result.output.contains("\u001B"))
        assertTrue(report.render().contains("- FAILED npm run test (exit 1)"))
        assertEquals(listOf("sb1"), sandbox.killed)
    }

    @Test
    fun aFailedInstallIsReportedOnceAndTheChecksAfterItAreSkipped() {
        val sandbox = FakeSandbox()
        sandbox.onExec = { cmd -> if (cmd.contains("sh -c 'npm ci'")) ExecResult("", "network down", 1) else ExecResult("", "", 0) }

        val report = verify(sandbox, FakeVerifyFiles(*npmTwoScripts))

        assertEquals(VerifyState.FAILED, report.state)
        assertEquals(listOf(CheckStatus.FAILED, CheckStatus.SKIPPED, CheckStatus.SKIPPED), report.results.map { it.status })
        assertEquals(1, sandbox.commands.count { it.contains("sh -c 'npm ci'") })
        assertFalse(sandbox.commands.any { it.contains("npm run lint") })
    }

    @Test
    fun exitCode124MeansTheCheckTimedOut() {
        val sandbox = FakeSandbox()
        sandbox.onExec = { cmd -> if (cmd.contains("npm run test")) ExecResult("partial", "", 124) else ExecResult("", "", 0) }

        val report = verify(sandbox, FakeVerifyFiles(*npm))

        assertEquals(CheckStatus.TIMED_OUT, report.results.single().status)
        assertEquals(VerifyState.FAILED, report.state)
    }

    @Test
    fun aSandboxCallThatThrowsIsAnErrorAndStopsTheRest() {
        val sandbox = FakeSandbox()
        sandbox.onExec = { cmd -> if (cmd.contains("npm run lint")) throw IllegalStateException("boom") else ExecResult("", "", 0) }

        val report = verify(sandbox, FakeVerifyFiles(*npmTwoScripts))

        assertEquals(listOf(CheckStatus.ERROR, CheckStatus.SKIPPED), report.results.map { it.status })
        assertTrue(report.results.first().note!!.contains("boom"))
        assertEquals(listOf("sb1"), sandbox.killed)
    }

    @Test
    fun aSandboxThatCannotStartMeansNotRun() {
        val sandbox = FakeSandbox()
        sandbox.createError = IllegalStateException("no capacity")

        val report = verify(sandbox, FakeVerifyFiles(*npm))

        assertEquals(VerifyState.NOT_RUN, report.state)
        assertTrue(report.message!!.contains("Could not start the sandbox: no capacity"))
        assertTrue(sandbox.killed.isEmpty())
    }

    @Test
    fun aFailedUploadMeansNotRunAndStillDeletesTheSandbox() {
        val sandbox = FakeSandbox()
        sandbox.onExec = { ExecResult("", "disk full", 1) }

        val report = verify(sandbox, FakeVerifyFiles(*npm))

        assertEquals(VerifyState.NOT_RUN, report.state)
        assertTrue(report.message!!.contains("Upload failed (exit 1): disk full"))
        assertEquals(listOf("sb1"), sandbox.killed)
        assertEquals(1, sandbox.commands.size)
    }

    @Test
    fun cancellingStillDeletesTheSandboxAndRethrows() {
        val sandbox = FakeSandbox()
        sandbox.onExec = { cmd -> if (cmd.contains("npm run test")) throw CancellationException("stop") else ExecResult("", "", 0) }

        val cancelled = runBlocking {
            try {
                SandboxVerifier(sandbox, FakeVerifyFiles(*npm)).verify()
                false
            } catch (e: CancellationException) {
                true
            }
        }

        assertTrue(cancelled)
        assertEquals(listOf("sb1"), sandbox.killed)
    }

    @Test
    fun unsavedEditsDecideWhichChecksExistAndWhatIsRun() {
        val sandbox = FakeSandbox()
        val files = FakeVerifyFiles("package.json" to "{}", "package-lock.json" to "{}")

        val none = verify(FakeSandbox(), files)
        val withEdit = verify(sandbox, files, overrides = mapOf("package.json" to """{"scripts":{"test":"jest"}}"""))

        assertEquals(VerifyState.NO_CHECKS, none.state)
        assertEquals(VerifyState.PASSED, withEdit.state)
    }

    @Test
    fun theUsersOwnCommandsAreRunInstead() {
        val sandbox = FakeSandbox()

        val report = verify(sandbox, FakeVerifyFiles(*npm), custom = listOf("make verify"))

        assertEquals(CheckSource.OVERRIDE, report.checkSource)
        assertEquals("make verify", report.results.single().check.command)
        assertFalse(sandbox.commands.any { it.contains("npm ci") })
    }

    @Test
    fun aProjectTooBigToUploadIsNotRunAndNoSandboxIsStarted() {
        val sandbox = FakeSandbox()

        val report = verify(sandbox, FakeVerifyFiles(*npm), limits = BundleLimits(maxFiles = 1))

        assertEquals(VerifyState.NOT_RUN, report.state)
        assertTrue(sandbox.created.isEmpty())
    }

    @Test
    fun longOutputIsCutToItsEnd() {
        val sandbox = FakeSandbox()
        sandbox.onExec = { cmd ->
            if (cmd.contains("npm run test")) ExecResult("", "noise ".repeat(100) + "THE REAL ERROR", 2) else ExecResult("", "", 0)
        }

        val output = verify(sandbox, FakeVerifyFiles(*npm), outputChars = 50).results.single().output

        assertTrue(output.startsWith("..."))
        assertTrue(output.endsWith("THE REAL ERROR"))
        assertTrue(output.length <= 53)
    }

    @Test
    fun progressLinesFollowTheRun() {
        val lines = ArrayList<String>()

        verify(FakeSandbox(), FakeVerifyFiles(*npm), progress = { lines += it })

        assertEquals(
            listOf("Starting the sandbox", "Uploading 3 files", "Installing: npm ci", "Running npm run test (1 of 1)"),
            lines,
        )
    }

    @Test
    fun theTextReportNamesFilesThatWereNotUploaded() {
        val files = FakeVerifyFiles(*npm, "huge.txt" to "x".repeat(50))

        val report = verify(FakeSandbox(), files, limits = BundleLimits(maxFileBytes = 40))

        assertTrue(report.render().contains("Not uploaded: huge.txt"))
    }
}
