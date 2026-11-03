package ai.lily.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestParserTest {
    private fun ok(text: String): PluginManifest = when (val r = ManifestParser.parse(text)) {
        is ParseResult.Ok -> r.manifest
        is ParseResult.Invalid -> throw AssertionError("expected a valid manifest, got ${r.errors}")
    }

    private fun errors(text: String): List<String> = when (val r = ManifestParser.parse(text)) {
        is ParseResult.Ok -> throw AssertionError("expected an invalid manifest")
        is ParseResult.Invalid -> r.errors
    }

    private fun assertMentions(errors: List<String>, part: String) {
        assertTrue("expected an error mentioning '$part', got $errors", errors.any { it.contains(part) })
    }

    @Test
    fun aValidManifestIsReadFully() {
        val m = ok(TestManifests.json())
        assertEquals("test.plugin", m.id)
        assertEquals(listOf<Permission>(Permission.ExternalApp("com.example.app")), m.permissions)
        val tool = m.tools.single()
        assertEquals("run", tool.name)
        assertEquals("command", tool.params.single().name)
        assertTrue(tool.params.single().required)
        assertEquals(50, tool.params.single().maxLength)
        assertEquals(ExtraValue.Text("{command}"), tool.action.extras["com.example.CMD"])
        assertEquals(ExtraValue.Flag(true), tool.action.extras["com.example.BG"])
        assertEquals("stdout", tool.action.result?.outputKey)
        assertEquals(-1, tool.action.result?.errorCodeOk)
        assertEquals(5000L, tool.action.timeoutMs)
    }

    @Test
    fun theBundledTermuxPluginIsValid() {
        val file = listOf("src/main/assets/plugins/termux.json", "app/src/main/assets/plugins/termux.json")
            .map { File(it) }.firstOrNull { it.isFile }
            ?: throw AssertionError("termux.json not found from ${File(".").absolutePath}")
        val m = ok(file.readText())
        assertEquals("lily.termux", m.id)
        assertEquals(listOf<Permission>(Permission.ExternalApp("com.termux")), m.permissions)
        val tool = m.tools.single()
        assertEquals("terminal", tool.name)
        assertEquals("com.termux", tool.action.packageName)
        assertEquals(ExtraValue.TextList(listOf("-c", "{command}")), tool.action.extras["com.termux.RUN_COMMAND_ARGUMENTS"])
        assertEquals(ExtraValue.Flag(true), tool.action.extras["com.termux.RUN_COMMAND_BACKGROUND"])
        assertEquals("com.termux.RUN_COMMAND_PENDING_INTENT", tool.action.result?.pendingIntentExtra)
    }

    @Test
    fun aNullResultMeansFireAndForget() {
        assertNull(ok(TestManifests.json(result = "null")).tools.single().action.result)
    }

    @Test
    fun notJsonAndTooLargeAreRejected() {
        assertMentions(errors("{nope"), "not valid JSON")
        assertMentions(errors("x".repeat(ManifestParser.MAX_BYTES + 1)), "larger than")
    }

    @Test
    fun wrongSchemaAndBadIdAndBadVersionAreRejected() {
        assertMentions(errors(TestManifests.json().replace("\"schema\": 1", "\"schema\": 2")), "schema")
        assertMentions(errors(TestManifests.json(id = "Bad Id")), "id must be")
        assertMentions(errors(TestManifests.json(id = "../evil")), "id must be")
        assertMentions(errors(TestManifests.json(version = "one")), "version")
    }

    @Test
    fun aToolMayOnlyTargetAnAppItAsksPermissionFor() {
        val e = errors(TestManifests.json(pkg = "com.other.app"))
        assertMentions(e, "com.other.app")
        assertMentions(e, "does not ask for")
    }

    @Test
    fun pluginsCannotTargetLilyOrAndroid() {
        for (pkg in listOf("ai.lily.app", "ai.lily.app.debug", "android.settings", "com.android.settings")) {
            val e = errors(TestManifests.json(permissions = "\"external-app:$pkg\"", pkg = pkg))
            assertMentions(e, "cannot target")
        }
    }

    @Test
    fun unknownPermissionsAreRejected() {
        assertMentions(errors(TestManifests.json(permissions = "\"read-everything\"")), "not a permission")
    }

    @Test
    fun anUnknownPlaceholderIsAnError() {
        val e = errors(TestManifests.json(extras = """{"com.example.CMD":"{command} {oops}"}"""))
        assertMentions(e, "{oops}")
    }

    @Test
    fun onlyTheAndroidServiceActionIsKnown() {
        assertMentions(errors(TestManifests.json(kind = "run-code")), "action.kind")
    }

    @Test
    fun badExtraValuesAreRejected() {
        assertMentions(errors(TestManifests.json(extras = """{"a.b":{"x":1}}""")), "must be text")
        assertMentions(errors(TestManifests.json(extras = """{"bad key":"x"}""")), "not allowed")
        assertMentions(errors(TestManifests.json(extras = """{"a.b":["x",1]}""")), "must be text")
    }

    @Test
    fun hiddenAndDirectionChangingCharactersAreRejectedInShownText() {
        assertMentions(errors(TestManifests.json(description = "safe\u202Etext")), "invisible")
        assertMentions(errors(TestManifests.json(description = "zero\u200Bwidth")), "invisible")
    }

    @Test
    fun paramProblemsAreRejected() {
        val twice = """[{"name":"command","required":true},{"name":"command"}]"""
        assertMentions(errors(TestManifests.json(params = twice)), "listed twice")
        val huge = """[{"name":"command","maxLength":999999}]"""
        assertMentions(errors(TestManifests.json(params = huge)), "maxLength")
    }

    @Test
    fun anIncompleteResultIsRejected() {
        assertMentions(errors(TestManifests.json(result = """{"bundleExtra":"result"}""")), "pendingIntentExtra")
    }

    @Test
    fun promptCommandsAndSnippetsAreReadAndChecked() {
        val good = """
            {"schema":1,"id":"lily.extras","name":"Extras","version":"0.1.0",
             "promptCommands":[{"name":"explain","description":"Explain code","template":"Explain this {language} code:\n{selection}"}],
             "snippets":[{"prefix":"fn","description":"function","body":"fun name() {}"}]}
        """.trimIndent()
        val m = ok(good)
        assertEquals("explain", m.promptCommands.single().name)
        assertEquals("fn", m.snippets.single().prefix)
        assertTrue(m.tools.isEmpty() && m.permissions.isEmpty())

        val bad = good.replace("{selection}", "{clipboard}")
        assertMentions(errors(bad), "{clipboard}")
    }
}
