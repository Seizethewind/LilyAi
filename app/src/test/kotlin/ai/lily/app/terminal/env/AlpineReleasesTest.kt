package ai.lily.app.terminal.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AlpineReleasesTest {
    private val sha = "b".repeat(64)

    private val yaml = """
        ---
        -
          title: "Netboot"
          flavor: alpine-netboot
          iso: alpine-netboot-3.21.3-aarch64.tar.gz
          size: 1111
          sha256: ${"c".repeat(64)}
        -
          title: "Mini root filesystem"
          desc: "Minimal root filesystem"
          date: 2025-02-14
          flavor: alpine-minirootfs
          iso: alpine-minirootfs-3.21.3-aarch64.tar.gz
          size: 3837340
          sha256: $sha
          sha512: ${"d".repeat(128)}
    """.trimIndent()

    @Test fun picksTheMiniRootFileSystem() {
        val r = AlpineReleases.release(yaml, EnvArch.ARM64)
        val a = r.assetFor(EnvArch.ARM64)!!
        assertEquals("3.21.3", r.version)
        assertEquals("https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz", a.url)
        assertEquals(sha, a.sha256)
        assertEquals(3837340L, a.sizeBytes)
    }

    @Test fun readsInlineListMarkersAndQuotes() {
        val inline = "- flavor: \"alpine-minirootfs\"\n  iso: 'alpine-minirootfs-3.20.0-armv7.tar.gz'\n  size: 5000\n  sha256: $sha\n"
        assertEquals("3.20.0", AlpineReleases.release(inline, EnvArch.ARM).version)
    }

    @Test fun showsTheStartOfTheFileWhenTheEntryIsMissing() {
        try {
            AlpineReleases.release("something: else\nentirely: different", EnvArch.ARM64)
            fail("expected an error")
        } catch (e: EnvException) {
            assertEquals(EnvFailure.BadManifest, e.failure)
            assertTrue(e.message!!.contains("entirely: different"))
        }
    }

    @Test fun refusesAnUnsafeFileName() {
        val bad = yaml.replace("alpine-minirootfs-3.21.3-aarch64.tar.gz", "../evil.tar.gz")
        try {
            AlpineReleases.release(bad, EnvArch.ARM64)
            fail("expected an error")
        } catch (e: EnvException) {
            assertEquals(EnvFailure.BadManifest, e.failure)
        }
    }

    @Test fun refusesAShortChecksum() {
        try {
            AlpineReleases.release(yaml.replace(sha, "abc"), EnvArch.ARM64)
            fail("expected an error")
        } catch (e: EnvException) {
            assertEquals(EnvFailure.BadManifest, e.failure)
        }
    }
}
