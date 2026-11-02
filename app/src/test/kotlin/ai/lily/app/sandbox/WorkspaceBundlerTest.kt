package ai.lily.app.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class WorkspaceBundlerTest {
    /** Plays the upload commands the way a shell would, for the one command shape the bundler writes. */
    private fun replay(commands: List<String>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        val write = Regex("^printf '%s' '([A-Za-z0-9+/=]*)' \\| base64 -d (>>?) '(.*)'$")
        for (command in commands) {
            for (segment in command.split(" && ")) {
                val m = write.matchEntire(segment) ?: continue
                val (data, op, path) = m.destructured
                val bytes = Base64.getDecoder().decode(data)
                out[path] = if (op == ">") bytes else (out[path] ?: ByteArray(0)) + bytes
            }
        }
        return out
    }

    private fun ready(result: BundleResult): WorkspaceBundle = (result as BundleResult.Ready).bundle

    private fun build(files: VerifyFiles, overrides: Map<String, String?> = emptyMap(), limits: BundleLimits = BundleLimits()) =
        runBlocking { WorkspaceBundler.build(files, overrides, limits) }

    @Test
    fun secretsAndBuildFoldersNeverLeave() {
        val files = FakeVerifyFiles(
            ".env" to "KEY=1", ".env.local" to "KEY=2", ".env.example" to "KEY=", "key.pem" to "-----", "id_rsa" to "x",
            "config/secrets.yml" to "x", "local.properties" to "sdk.dir=/x", "src/App.kt" to "fun a() {}",
            "package-lock.json" to "{}", "node_modules/x/index.js" to "x", ".git/config" to "x", ".ssh/known_hosts" to "x",
        )

        val plan = runBlocking { WorkspaceBundler.plan(files, emptyMap()) }

        assertEquals(listOf(".env.example", "package-lock.json", "src/App.kt"), plan.files.map { it.path })
    }

    @Test
    fun theCommandsRebuildEveryFileExactly() {
        val jar = ByteArray(300) { (it * 7).toByte() }
        val files = FakeVerifyFiles(mapOf("src/A.kt" to "fun a() = 1\n".toByteArray(), "gradle/wrapper/gradle-wrapper.jar" to jar, "top.txt" to "hi".toByteArray()))

        val bundle = ready(build(files))
        val rebuilt = replay(bundle.commands)

        assertEquals(setOf("/tmp/work/src/A.kt", "/tmp/work/gradle/wrapper/gradle-wrapper.jar", "/tmp/work/top.txt"), rebuilt.keys)
        assertArrayEquals(jar, rebuilt.getValue("/tmp/work/gradle/wrapper/gradle-wrapper.jar"))
        assertEquals("hi", String(rebuilt.getValue("/tmp/work/top.txt")))
        assertEquals(3, bundle.files.size)
        assertTrue(bundle.commands.first().startsWith("rm -rf '/tmp/work' && mkdir -p '/tmp/work'"))
        assertTrue(bundle.commands.any { it.contains("mkdir -p '/tmp/work/gradle/wrapper'") })
    }

    @Test
    fun aBigFileIsSplitIntoPiecesThatFitTheCommandLimit() {
        val big = ByteArray(100_000) { (it * 31).toByte() }
        val limits = BundleLimits()
        val files = FakeVerifyFiles(mapOf("data.bin" to big, "small.txt" to "x".toByteArray()))

        val bundle = ready(build(files, limits = limits))

        assertTrue(bundle.commands.all { it.length <= limits.maxCommandChars })
        assertTrue(bundle.commands.any { it.contains("base64 -d >> '/tmp/work/data.bin'") })
        assertArrayEquals(big, replay(bundle.commands).getValue("/tmp/work/data.bin"))
    }

    @Test
    fun unsavedTextReplacesDeletesAndAddsFiles() {
        val files = FakeVerifyFiles("a.txt" to "old", "gone.txt" to "bye", "keep.txt" to "same")

        val bundle = ready(build(files, mapOf("a.txt" to "new", "gone.txt" to null, "fresh.txt" to "added", ".env" to "SECRET=1")))
        val rebuilt = replay(bundle.commands).mapValues { String(it.value) }

        assertEquals(
            mapOf("/tmp/work/a.txt" to "new", "/tmp/work/keep.txt" to "same", "/tmp/work/fresh.txt" to "added"),
            rebuilt,
        )
    }

    @Test
    fun tooManyFilesOrTooManyBytesMeansNothingIsUploaded() {
        val three = FakeVerifyFiles("a" to "1", "b" to "2", "c" to "3")
        assertTrue(build(three, limits = BundleLimits(maxFiles = 2)) is BundleResult.TooLarge)

        val heavy = FakeVerifyFiles("a" to "x".repeat(20))
        assertTrue(build(heavy, limits = BundleLimits(maxTotalBytes = 10)) is BundleResult.TooLarge)
    }

    @Test
    fun aFileOverThePerFileLimitIsLeftOutAndSaidSo() {
        val files = FakeVerifyFiles("big.txt" to "x".repeat(10), "small.txt" to "y")

        val bundle = ready(build(files, limits = BundleLimits(maxFileBytes = 5)))

        assertEquals(listOf("small.txt"), bundle.files)
        assertTrue(bundle.skipped.single().startsWith("big.txt"))
    }

    @Test
    fun aFileThatCannotBeReadIsLeftOutAndSaidSo() {
        val files = object : VerifyFiles {
            override suspend fun list() = listOf(VerifyFile("ok.txt", 2), VerifyFile("vanished.txt", 2))
            override suspend fun read(path: String): ByteArray? = if (path == "ok.txt") "ok".toByteArray() else null
        }

        val bundle = ready(build(files))

        assertEquals(listOf("ok.txt"), bundle.files)
        assertTrue(bundle.skipped.single().contains("vanished.txt"))
    }

    @Test
    fun quotesInAPathAreEscapedForTheShell() {
        val bundle = ready(build(FakeVerifyFiles("it's.txt" to "x")))

        assertEquals(setOf("/tmp/work/it'\\''s.txt"), replay(bundle.commands).keys)
    }

    @Test
    fun emptyFilesAreCreatedAndScriptsAreMadeExecutable() {
        val bundle = ready(build(FakeVerifyFiles("empty.txt" to "", "run.sh" to "#!/bin/sh\necho hi\n")))
        val all = bundle.commands.joinToString(" && ")

        assertTrue(all.contains(": > '/tmp/work/empty.txt'"))
        assertTrue(all.contains("chmod +x '/tmp/work/run.sh'"))
        assertFalse(all.contains("chmod +x '/tmp/work/empty.txt'"))
    }

    @Test
    fun unsafePathsAreRejected() {
        for (bad in listOf("", "/etc/passwd", "../x", "a/../b", "a//b", "./a", "a/", "a\nb", "a\\b")) {
            assertFalse("'$bad' should be unsafe", VerifyFilter.isSafePath(bad))
        }
        assertTrue(VerifyFilter.isSafePath("src/main/App.kt"))
    }

    @Test
    fun badOptionsAreRefused() {
        assertTrue(runCatching { BundleLimits(pieceChars = 10) }.isFailure)
        assertTrue(runCatching { BundleLimits(pieceChars = 60_000, maxCommandChars = 61_000) }.isFailure)
        assertTrue(runCatching { BundleLimits(pieceChars = 8, maxCommandChars = 5_000) }.isSuccess)
    }
}
