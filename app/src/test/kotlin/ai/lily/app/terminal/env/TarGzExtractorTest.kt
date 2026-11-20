package ai.lily.app.terminal.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

class TarGzExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun extract(b: TarBuilder, cutOff: Int? = null): File {
        val dest = File(tmp.root, "out")
        TarGzExtractor.extract(b.writeGz(File(tmp.root, "a.tar.gz"), cutOff), dest)
        return dest
    }

    private fun assertArchiveError(block: () -> Unit) {
        try {
            block()
            fail("expected an archive error")
        } catch (e: EnvException) {
            assertEquals(EnvFailure.Archive, e.failure)
        }
    }

    @Test fun unpacksFilesFoldersAndExecuteBits() {
        val out = extract(
            TarBuilder().dir("bin/").file("bin/tool", "run", mode = 0b111_101_101).file("etc/motd", "hi").dir("./usr/"),
        )
        assertEquals("run", File(out, "bin/tool").readText())
        assertTrue(File(out, "bin/tool").canExecute())
        assertFalse(File(out, "etc/motd").canExecute())
        assertTrue(File(out, "usr").isDirectory)
    }

    @Test fun createsLinksAsTheyAreWithoutFollowingThem() {
        val out = extract(TarBuilder().dir("bin/").symlink("bin/sh", "/bin/busybox"))
        val link = File(out, "bin/sh").toPath()
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("/bin/busybox", Files.readSymbolicLink(link).toString())
        // Dangling on the host is expected: the target only exists inside the guest.
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun refusesNamesThatLeaveTheTargetFolder() {
        assertArchiveError { extract(TarBuilder().file("../escape", "x")) }
        assertArchiveError { extract(TarBuilder().file("a/../../escape", "x")) }
        assertFalse(File(tmp.root, "escape").exists())
    }

    @Test fun treatsAbsoluteNamesAsRelativeToTheTarget() {
        val out = extract(TarBuilder().file("/etc/passwd", "root"))
        assertEquals("root", File(out, "etc/passwd").readText())
    }

    @Test fun refusesToWriteThroughALink() {
        val outside = tmp.newFolder("outside")
        assertArchiveError {
            extract(TarBuilder().symlink("evil", outside.absolutePath).file("evil/owned", "x"))
        }
        assertFalse(File(outside, "owned").exists())
    }

    @Test fun hardLinkMustPointAtAnEarlierFile() {
        val out = extract(TarBuilder().file("a", "data").hardlink("b", "a"))
        assertEquals("data", File(out, "b").readText())
        assertArchiveError { extract(TarBuilder().hardlink("b", "missing")) }
    }

    @Test fun readsPaxAndGnuLongNames() {
        val long = "usr/share/" + "x".repeat(150) + "/file.txt"
        val pax = extract(TarBuilder().fileWithPaxPath(long, "pax".toByteArray()))
        assertEquals("pax", File(pax, long).readText())
        File(tmp.root, "out").deleteRecursively()
        val gnu = extract(TarBuilder().fileWithGnuLongName(long, "gnu".toByteArray()))
        assertEquals("gnu", File(gnu, long).readText())
    }

    @Test fun skipsDeviceFiles() {
        val dest = File(tmp.root, "out")
        val r = TarGzExtractor.extract(
            TarBuilder().device("dev/null").file("ok", "1").writeGz(File(tmp.root, "d.tar.gz")),
            dest,
        )
        assertEquals(1, r.skipped)
        assertFalse(File(dest, "dev/null").exists())
        assertTrue(File(dest, "ok").exists())
    }

    @Test fun stopsAtTheSizeLimit() {
        val b = TarBuilder().file("big", ByteArray(5000))
        assertArchiveError {
            TarGzExtractor.extract(
                b.writeGz(File(tmp.root, "b.tar.gz")), File(tmp.root, "out"),
                TarGzExtractor.Limits(maxBytes = 1000),
            )
        }
    }

    @Test fun reportsACutOffArchive() {
        val full = TarBuilder().file("a", ByteArray(20_000) { (it % 251).toByte() })
        val size = full.writeGz(File(tmp.root, "full.tar.gz")).length().toInt()
        assertArchiveError { extract(full, cutOff = size / 2) }
    }

    @Test fun reportsSomethingThatIsNotGzip() {
        val f = tmp.newFile("junk.tar.gz").apply { writeText("this is not a gzip file at all") }
        assertArchiveError { TarGzExtractor.extract(f, File(tmp.root, "out")) }
    }
}
