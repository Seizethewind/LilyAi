package ai.lily.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The optional "screen" field of a manifest, and how the bundled Discord plugin is brought up to date to get it. */
class PluginScreenTest {
    private val text = File("src/main/assets/plugins/discord.json").readText()
    private val screenField = Regex("\"screen\"\\s*:\\s*\"discord\"\\s*,")
    private val versionField = Regex("\"version\"\\s*:\\s*\"[^\"]*\"")
    private val id = "lily.discord"
    private val api = "http:discord.com/api/v10/"

    private fun ok(manifest: String): PluginManifest = when (val r = ManifestParser.parse(manifest)) {
        is ParseResult.Ok -> r.manifest
        is ParseResult.Invalid -> throw AssertionError("expected a valid manifest, got ${r.errors}")
    }

    private fun errors(manifest: String): List<String> = when (val r = ManifestParser.parse(manifest)) {
        is ParseResult.Ok -> throw AssertionError("expected an invalid manifest")
        is ParseResult.Invalid -> r.errors
    }

    @Test
    fun theBundledDiscordPluginNamesItsScreen() {
        assertEquals("discord", ok(text).screen)
        assertTrue("discord" in ManifestParser.SCREENS)
    }

    @Test
    fun aManifestWithoutAScreenHasNone() {
        assertTrue(screenField.containsMatchIn(text))
        assertEquals("", ok(text.replace(screenField, "")).screen)
    }

    @Test
    fun aScreenTheAppDoesNotHaveIsRefused() {
        val e = errors(screenField.replace(text, "\"screen\": \"nope\","))
        assertTrue(e.toString(), e.any { it.contains("screen") && it.contains("discord") })
    }

    @Test
    fun aScreenThatIsNotTextIsRefused() {
        val e = errors(screenField.replace(text, "\"screen\": 5,"))
        assertTrue(e.toString(), e.any { it.contains("screen") })
    }

    @Test
    fun anUpdateGivesTheScreenAndKeepsWhatThePersonDecided() {
        val shippedVersion = ok(text).version
        val old = versionField.replace(text.replace(screenField, ""), "\"version\": \"1.0.0\"")
        val registry = PluginRegistry(InMemoryPluginStore())
        registry.install(old)
        registry.setEnabled(id, true)
        registry.grant(id, api)
        registry.setAutoApprove(id, "discord_list_channels", true)
        assertEquals("", registry.get(id)!!.manifest.screen)

        val result = registry.install(text)
        assertTrue((result as InstallResult.Installed).updated)
        val now = registry.get(id)!!
        assertEquals("discord", now.manifest.screen)
        assertEquals(shippedVersion, now.manifest.version)
        assertTrue(now.state.enabled)
        // An update never adds an allowance: only what the person had allowed is still allowed.
        assertEquals(setOf(api), now.state.granted)
        assertEquals(setOf("discord_list_channels"), now.state.autoApprove)
    }

    @Test
    fun aNewerBundledVersionIsRecognisedAndNothingStrangeReplacesAPlugin() {
        assertTrue(BundledUpdate.isNewer("1.1.0", "1.0.0"))
        assertTrue(BundledUpdate.isNewer("1.0.1", "1.0.0"))
        assertTrue(BundledUpdate.isNewer("2.0.0", "1.9.9"))
        assertTrue(BundledUpdate.isNewer("1.10.0", "1.9.0"))
        assertFalse(BundledUpdate.isNewer("1.0.0", "1.0.0"))
        assertFalse(BundledUpdate.isNewer("1.0.0", "1.1.0"))
        assertFalse(BundledUpdate.isNewer("1.0", "1.0.0"))
        assertFalse(BundledUpdate.isNewer("1.0.0", "one"))
        assertFalse(BundledUpdate.isNewer("1.0.x", "1.0.0"))
    }
}
