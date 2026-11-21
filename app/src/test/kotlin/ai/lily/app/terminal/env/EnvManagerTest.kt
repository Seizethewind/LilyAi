package ai.lily.app.terminal.env

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnvManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var layout: EnvLayout
    private lateinit var downloader: FakeDownloader
    private var space = Long.MAX_VALUE

    private fun goodArchive(marker: String = "v1") = TarBuilder()
        .dir("bin/").symlink("bin/sh", "/bin/busybox").file("bin/busybox", "bb")
        .dir("etc/").file("etc/os-release", marker)
        .writeGzBytes()

    private fun TarBuilder.writeGzBytes(): ByteArray = writeGz(File(tmp.root, "build-${System.nanoTime()}.tar.gz")).readBytes()

    private fun release(bytes: ByteArray, version: String = "1") = EnvRelease(
        version,
        mapOf("aarch64" to EnvAsset("https://example.com/x.tar.gz", sha256Of(bytes), bytes.size.toLong())),
    )

    private fun manager() = EnvManager(layout, downloader, freeSpace = { space }, now = { 42L })

    @Before fun setUp() {
        layout = EnvLayout(File(tmp.root, "env"))
        downloader = FakeDownloader()
    }

    private fun failureOf(block: suspend () -> Unit): EnvException {
        try {
            runBlocking { block() }
        } catch (e: EnvException) {
            return e
        }
        fail("expected an EnvException")
        throw IllegalStateException()
    }

    @Test fun startsMissing() {
        assertEquals(EnvState.Missing, manager().state.value)
    }

    @Test fun installsAndReportsReady() = runBlocking {
        val bytes = goodArchive()
        downloader.payload = bytes
        val m = manager()
        m.install(release(bytes), EnvArch.ARM64)
        assertEquals(EnvState.Ready("1", "aarch64"), m.state.value)
        assertTrue(File(layout.rootfs, "etc/os-release").isFile)
        assertFalse(layout.staging.exists())
        assertFalse(layout.partFile("1", "aarch64").exists())
        assertTrue(layout.stateFile.readText().contains("\"installedAt\":42"))
    }

    @Test fun wrongChecksumDeletesTheDownloadAndKeepsNothing() {
        val bytes = goodArchive()
        downloader.payload = bytes
        val bad = release(bytes).let { it.copy(assets = mapOf("aarch64" to it.assets.getValue("aarch64").copy(sha256 = "0".repeat(64)))) }
        val m = manager()
        val e = failureOf { m.install(bad, EnvArch.ARM64) }
        assertEquals(EnvFailure.Checksum, e.failure)
        assertFalse(layout.partFile("1", "aarch64").exists())
        assertFalse(layout.rootfs.exists())
        assertTrue(m.state.value is EnvState.Failed)
    }

    @Test fun resumesAPartialDownload() = runBlocking {
        val bytes = goodArchive()
        downloader.payload = bytes
        downloader.interruptAfter = bytes.size / 2
        val m = manager()
        val first = failureOf { m.install(release(bytes), EnvArch.ARM64) }
        assertEquals(EnvFailure.Network, first.failure)
        assertEquals((bytes.size / 2).toLong(), layout.partFile("1", "aarch64").length())

        m.install(release(bytes), EnvArch.ARM64)
        assertEquals((bytes.size / 2).toLong(), downloader.existingBytesSeen)
        assertTrue(m.state.value is EnvState.Ready)
    }

    @Test fun anArchiveWithoutAShellIsRefusedAndTheOldEnvironmentSurvives() = runBlocking {
        val good = goodArchive("old")
        downloader.payload = good
        val m = manager()
        m.install(release(good, "1"), EnvArch.ARM64)

        val noShell = TarBuilder().dir("etc/").file("etc/os-release", "new").writeGzBytes()
        downloader.payload = noShell
        val e = failureOf { m.install(release(noShell, "2"), EnvArch.ARM64) }
        assertEquals(EnvFailure.Archive, e.failure)
        assertFalse(layout.staging.exists())
        assertEquals("old", File(layout.rootfs, "etc/os-release").readText())
        // The failure is shown, and the disk still holds the old, working version.
        assertTrue(m.state.value is EnvState.Failed)
        assertEquals(EnvState.Ready("1", "aarch64"), m.inspect())
    }

    @Test fun notEnoughSpaceFailsBeforeDownloading() {
        val bytes = goodArchive()
        downloader.payload = bytes
        space = 10
        val e = failureOf { manager().install(release(bytes), EnvArch.ARM64) }
        assertEquals(EnvFailure.Disk, e.failure)
        assertEquals(0, downloader.calls)
    }

    @Test fun aBuildForAnotherCpuIsRefused() {
        val bytes = goodArchive()
        val e = failureOf { manager().install(release(bytes), EnvArch.X86_64) }
        assertEquals(EnvFailure.UnsupportedDevice, e.failure)
    }

    @Test fun ensureOnlyInstallsWhenNeeded() = runBlocking {
        val bytes = goodArchive()
        downloader.payload = bytes
        val m = manager()
        assertTrue(m.ensure(release(bytes), EnvArch.ARM64))
        assertFalse(m.ensure(release(bytes), EnvArch.ARM64))
        assertEquals(1, downloader.calls)

        // A newer release installs again; a missing shell (damage) installs again.
        val v2 = goodArchive("v2")
        downloader.payload = v2
        assertTrue(m.ensure(release(v2, "2"), EnvArch.ARM64))
        File(layout.rootfs, "bin/sh").delete()
        assertTrue(m.inspect() is EnvState.Broken)
        assertTrue(m.ensure(release(v2, "2"), EnvArch.ARM64))
        assertTrue(m.inspect() is EnvState.Ready)
    }

    @Test fun resetRemovesEverythingWithoutFollowingLinks() = runBlocking {
        val bytes = goodArchive()
        downloader.payload = bytes
        val m = manager()
        m.install(release(bytes), EnvArch.ARM64)
        // A link inside the environment that points at a real folder outside it must not have that folder emptied.
        val outside = tmp.newFolder("precious").also { File(it, "keep.txt").writeText("keep") }
        java.nio.file.Files.createSymbolicLink(File(layout.rootfs, "etc/trap").toPath(), outside.toPath())

        m.reset()
        assertEquals(EnvState.Missing, m.state.value)
        assertFalse(layout.rootfs.exists())
        assertFalse(layout.stateFile.exists())
        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test fun aFolderWithoutAVersionRecordIsBroken() {
        File(layout.rootfs, "etc").mkdirs()
        assertTrue(manager().inspect() is EnvState.Broken)
    }
}
