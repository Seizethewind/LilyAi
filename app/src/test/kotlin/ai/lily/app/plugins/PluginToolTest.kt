package ai.lily.app.plugins

import ai.lily.app.tools.Tool
import ai.lily.app.tools.ToolSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginToolTest {
    private class Rig(
        enabled: Boolean = true,
        grant: Boolean = true,
        auto: Boolean = false,
        answer: Boolean = true,
        manifest: String = TestManifests.json(),
    ) {
        val registry = PluginRegistry(InMemoryPluginStore())
        val bridge = FakeBridge()
        val gate = RecordingGate(answer)
        val id: String
        val tool: PluginTool

        init {
            val installed = registry.install(manifest) as InstallResult.Installed
            id = installed.plugin.manifest.id
            if (enabled) registry.setEnabled(id, true)
            if (grant) registry.grant(id, TestManifests.PERMISSION)
            if (auto) registry.setAutoApprove(id, "run", true)
            tool = PluginTool(id, "run", registry, bridge, gate)
        }

        fun run(vararg args: Pair<String, String>): String = runBlocking { tool.execute(mapOf(*args)) }
    }

    private fun text(call: ServiceCall, key: String): String =
        (call.extras[key] as ExtraValue.Text).value

    @Test
    fun itAsksFirstAndThenRunsWhenApproved() {
        val rig = Rig()
        val out = rig.run("command" to "ls -la")
        assertEquals(1, rig.gate.requests.size)
        val call = rig.bridge.calls.single()
        assertEquals("com.example.app", call.packageName)
        assertEquals("com.example.app.Service", call.className)
        assertEquals("com.example.RUN", call.intentAction)
        assertEquals("ls -la", text(call, "com.example.CMD"))
        assertEquals(ExtraValue.Flag(true), call.extras["com.example.BG"])
        assertTrue(out, out.startsWith("exit=0"))
        assertTrue(out.contains("out"))
    }

    @Test
    fun nothingRunsWhenThePersonSaysNo() {
        val rig = Rig(answer = false)
        val out = rig.run("command" to "ls")
        assertTrue(out, out.startsWith("denied"))
        assertTrue(rig.bridge.calls.isEmpty())
    }

    @Test
    fun theDefaultGateDeniesEverything() {
        val rig = Rig()
        val tool = PluginTool(rig.id, "run", rig.registry, rig.bridge, DenyAllGate)
        val out = runBlocking { tool.execute(mapOf("command" to "ls")) }
        assertTrue(out, out.startsWith("denied"))
        assertTrue(rig.bridge.calls.isEmpty())
    }

    @Test
    fun aPluginThatIsOffRunsNothingAndAsksNothing() {
        val rig = Rig(enabled = false)
        val out = rig.run("command" to "ls")
        assertTrue(out, out.startsWith("error:") && out.contains("turned off"))
        assertTrue(rig.gate.requests.isEmpty() && rig.bridge.calls.isEmpty())
    }

    @Test
    fun aPermissionThatWasNotAllowedRunsNothing() {
        val rig = Rig(grant = false)
        val out = rig.run("command" to "ls")
        assertTrue(out, out.startsWith("error:") && out.contains("not allowed"))
        assertTrue(rig.gate.requests.isEmpty() && rig.bridge.calls.isEmpty())
    }

    @Test
    fun dontAskAgainSkipsTheQuestionButNotTheChecks() {
        val rig = Rig(auto = true, answer = false)
        rig.run("command" to "ls")
        assertTrue(rig.gate.requests.isEmpty())
        assertEquals(1, rig.bridge.calls.size)
    }

    @Test
    fun badArgumentsAreRefusedWithAHelpfulMessage() {
        val rig = Rig()
        assertTrue(rig.run("command" to "x", "cwd" to "/").contains("unknown argument 'cwd'"))
        assertTrue(rig.run().contains("'command' is required"))
        assertTrue(rig.run("command" to "   ").contains("'command' is required"))
        assertTrue(rig.run("command" to "x".repeat(51)).contains("longer than 50"))
        assertTrue(rig.run("command" to "a\u0000b").contains("null character"))
        assertTrue(rig.gate.requests.isEmpty() && rig.bridge.calls.isEmpty())
    }

    @Test
    fun aValueThatLooksLikeAPlaceholderIsNotExpandedAgain() {
        val rig = Rig()
        rig.run("command" to "echo {command} {x} \$HOME")
        assertEquals("echo {command} {x} \$HOME", text(rig.bridge.calls.single(), "com.example.CMD"))
    }

    @Test
    fun anOptionalValueThatWasNotGivenIsLeftOutNotSentBlank() {
        val params = """[{"name":"command","required":true},{"name":"dir","required":false}]"""
        val extras = """{"com.example.CMD":"{command}","com.example.DIR":"{dir}"}"""
        val rig = Rig(manifest = TestManifests.json(params = params, extras = extras))
        rig.run("command" to "ls")
        assertEquals(setOf("com.example.CMD"), rig.bridge.calls.single().extras.keys)
        rig.run("command" to "ls", "dir" to "/tmp")
        assertEquals("/tmp", text(rig.bridge.calls.last(), "com.example.DIR"))
    }

    @Test
    fun theQuestionShowsEveryValueAndMakesHiddenCharactersVisible() {
        val rig = Rig()
        rig.run("command" to "ls\u202E")
        val request = rig.gate.requests.single()
        assertEquals("test.plugin", request.pluginId)
        assertEquals("run", request.toolName)
        assertEquals("com.example.app", request.targetPackage)
        assertEquals("ls\\u202e", request.details.first { it.first == "com.example.CMD" }.second)
        assertEquals("true", request.details.first { it.first == "com.example.BG" }.second)
    }

    @Test
    fun anErrorFromTheOtherAppIsReportedAsAnError() {
        val rig = Rig()
        rig.bridge.reply = ServiceReply.Done("", null, null, 7, "boom")
        val out = rig.run("command" to "ls")
        assertTrue(out, out.startsWith("error:") && out.contains("boom"))
    }

    @Test
    fun theNoErrorCodeIsNotAnError() {
        val rig = Rig()
        rig.bridge.reply = ServiceReply.Done("hi", "", 0, -1, null)
        assertEquals("exit=0\nhi", rig.run("command" to "ls"))
    }

    @Test
    fun stderrIsShownWhenThereIsSome() {
        val rig = Rig()
        rig.bridge.reply = ServiceReply.Done("o", "e", 1, -1, null)
        assertEquals("exit=1\no\n[stderr]\ne", rig.run("command" to "ls"))
    }

    @Test
    fun longOutputKeepsTheEndWhereErrorsAre() {
        val rig = Rig()
        rig.bridge.reply = ServiceReply.Done("a".repeat(20_000) + "THE END", null, 0, -1, null)
        val out = rig.run("command" to "ls")
        assertTrue(out.endsWith("THE END"))
        assertTrue(out.contains("cut from the start"))
        assertTrue(out.length < 16_500)
    }

    @Test
    fun failuresBecomeErrorTextAndNeverThrow() {
        val rig = Rig()
        rig.bridge.reply = ServiceReply.Failed(FailReason.NOT_ALLOWED, "nope")
        val refused = rig.run("command" to "ls")
        assertTrue(refused, refused.startsWith("error:") && refused.contains("nope"))

        rig.bridge.failure = IllegalStateException("kaboom")
        val thrown = rig.run("command" to "ls")
        assertTrue(thrown, thrown.startsWith("error:") && thrown.contains("kaboom"))
    }

    @Test
    fun fireAndForgetReportsThatItWasSent() {
        val rig = Rig(manifest = TestManifests.json(result = "null"))
        rig.bridge.reply = ServiceReply.Sent
        val out = rig.run("command" to "ls")
        assertTrue(out, out.startsWith("sent"))
        assertNull(rig.bridge.calls.single().result)
    }

    @Test
    fun takingAPermissionBackStopsAToolThatIsAlreadyRegistered() {
        val rig = Rig()
        rig.registry.revoke(rig.id, TestManifests.PERMISSION)
        val out = rig.run("command" to "ls")
        assertTrue(out, out.startsWith("error:"))
        assertTrue(rig.bridge.calls.isEmpty())
    }

    @Test
    fun turningThePluginOffStopsAToolThatIsAlreadyRegistered() {
        val rig = Rig()
        rig.registry.setEnabled(rig.id, false)
        assertTrue(rig.run("command" to "ls").contains("turned off"))
        assertTrue(rig.bridge.calls.isEmpty())
    }

    @Test
    fun anUpdateTakesEffectOnTheNextCall() {
        val rig = Rig()
        rig.registry.install(TestManifests.json(extras = """{"com.example.OTHER":"{command}"}"""))
        rig.run("command" to "ls")
        assertEquals(setOf("com.example.OTHER"), rig.bridge.calls.single().extras.keys)
    }

    private class CoreTool : Tool {
        override val name = "run"
        override suspend fun execute(args: Map<String, String>): String = "core"
    }

    @Test
    fun aPluginToolIsRegisteredOnlyWhenOnAndAllowedAndNeverReplacesACoreTool() {
        val rig = Rig()
        val empty = ToolSystem()
        val report = PluginHost(rig.registry, rig.bridge, rig.gate).registerInto(empty)
        assertEquals(listOf("run"), report.registered)
        assertNotNull(empty.get("run"))

        val withCore = ToolSystem().apply { register(CoreTool()) }
        val clash = PluginHost(rig.registry, rig.bridge, rig.gate).registerInto(withCore)
        assertTrue(clash.registered.isEmpty())
        assertTrue(clash.skipped.single().contains("already exists"))
        assertTrue(runBlocking { withCore.get("run")!!.execute(emptyMap()) } == "core")

        val notAllowed = Rig(grant = false)
        val skipped = PluginHost(notAllowed.registry, notAllowed.bridge, notAllowed.gate).registerInto(ToolSystem())
        assertTrue(skipped.registered.isEmpty())
        assertTrue(skipped.skipped.single().contains("not allowed"))

        val off = Rig(enabled = false)
        val nothing = PluginHost(off.registry, off.bridge, off.gate).registerInto(ToolSystem())
        assertTrue(nothing.registered.isEmpty() && nothing.skipped.isEmpty())
        assertFalse(nothing.registered.contains("run"))
    }
}
