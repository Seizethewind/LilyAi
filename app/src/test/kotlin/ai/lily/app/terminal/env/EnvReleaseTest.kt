package ai.lily.app.terminal.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class EnvReleaseTest {
    private val sha = "a".repeat(64)

    private fun manifest(
        schema: Int = 1,
        version: String = "2026.11.1",
        url: String = "https://example.com/rootfs.tar.gz",
        hash: String = sha,
        size: Long = 3_500_000,
    ) = """{"schema":$schema,"version":"$version","assets":{"aarch64":{"url":"$url","sha256":"$hash","size":$size}}}"""

    private fun assertBad(json: String) {
        try {
            EnvReleaseParser.parse(json)
            fail("expected a bad manifest")
        } catch (e: EnvException) {
            assertEquals(EnvFailure.BadManifest, e.failure)
        }
    }

    @Test fun readsAValidManifest() {
        val r = EnvReleaseParser.parse(manifest())
        assertEquals("2026.11.1", r.version)
        assertEquals(3_500_000L, r.assetFor(EnvArch.ARM64)?.sizeBytes)
        assertNull(r.assetFor(EnvArch.X86_64))
    }

    @Test fun refusesAnythingLoose() {
        assertBad("not json")
        assertBad(manifest(schema = 2))
        assertBad(manifest(url = "http://example.com/rootfs.tar.gz"))
        assertBad(manifest(hash = "abc"))
        assertBad(manifest(hash = "A".repeat(64)))
        assertBad(manifest(size = 0))
        assertBad(manifest(size = EnvReleaseParser.MAX_ARCHIVE_BYTES + 1))
        assertBad(manifest(version = "../x"))
        assertBad("""{"schema":1,"version":"1","assets":{}}""")
    }

    @Test fun picksTheFirstSupportedAbi() {
        assertEquals(EnvArch.ARM64, EnvArch.fromAbis(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(EnvArch.ARM, EnvArch.fromAbis(listOf("mips", "armeabi-v7a")))
        assertNull(EnvArch.fromAbis(listOf("mips")))
    }
}
