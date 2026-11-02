package ai.lily.app.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real files in a temporary folder. */
class DiskVerifyFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val jar = ByteArray(64) { (it * 3 + 200).toByte() }

    private fun workspace(): File {
        val ws = tmp.newFolder("ws")
        putFile(ws, "src/App.kt", "fun a() {}\n".toByteArray())
        putFile(ws, "package-lock.json", "{}".toByteArray())
        putFile(ws, "gradle/wrapper/gradle-wrapper.jar", jar)
        putFile(ws, ".env", "KEY=1".toByteArray())
        putFile(ws, "keys/server.pem", "-----".toByteArray())
        putFile(ws, "node_modules/x/index.js", "x".toByteArray())
        putFile(ws, "build/out.txt", "y".toByteArray())
        putFile(ws, ".git/config", "z".toByteArray())
        return ws
    }

    @Test
    fun onlyFilesThatMayLeaveAreListed() = runBlocking<Unit> {
        val listed = DiskVerifyFiles(workspace()).list()

        assertEquals(setOf("src/App.kt", "package-lock.json", "gradle/wrapper/gradle-wrapper.jar"), listed.map { it.path }.toSet())
        assertEquals(64L, listed.first { it.path.endsWith(".jar") }.size)
    }

    @Test
    fun binaryFilesAreReadByteForByte() = runBlocking<Unit> {
        assertArrayEquals(jar, DiskVerifyFiles(workspace()).read("gradle/wrapper/gradle-wrapper.jar"))
    }

    @Test
    fun secretsOutsidePathsAndMissingFilesCannotBeRead() = runBlocking<Unit> {
        val ws = workspace()
        File(tmp.root, "outside.txt").writeText("secret")
        val files = DiskVerifyFiles(ws)

        assertNull(files.read(".env"))
        assertNull(files.read("keys/server.pem"))
        assertNull(files.read("../outside.txt"))
        assertNull(files.read("src"))
        assertNull(files.read("missing.kt"))
    }

    @Test
    fun aFileOverTheReadLimitIsNotRead() = runBlocking<Unit> {
        assertNull(DiskVerifyFiles(workspace(), maxReadBytes = 10).read("gradle/wrapper/gradle-wrapper.jar"))
    }

    @Test
    fun theWholeFlowWorksOnRealFiles() = runBlocking<Unit> {
        val ws = workspace()
        putFile(ws, "package.json", """{"scripts":{"test":"jest"}}""".toByteArray())
        val sandbox = FakeSandbox()

        val report = SandboxVerifier(sandbox, DiskVerifyFiles(ws)).verify()

        assertEquals(VerifyState.PASSED, report.state)
        assertEquals(4, report.fileCount)
        assertEquals(listOf("sb1"), sandbox.killed)
    }
}
