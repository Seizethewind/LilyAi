package ai.lily.app.plugins

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the bundled Git control manifest: it parses, and nothing the person or Lily types can reach the shell string. */
class GitPluginTest {
    private val text = File("src/main/assets/plugins/termux-git.json").readText()
    private val id = "lily.termux.git"
    private val args = "com.termux.RUN_COMMAND_ARGUMENTS"

    private class Rig(text: String, id: String) {
        val registry = PluginRegistry(InMemoryPluginStore())
        val bridge = FakeBridge()
        val gate = RecordingGate(true)
        val id = id

        init {
            registry.install(text)
            registry.setEnabled(id, true)
            registry.grant(id, "external-app:com.termux")
        }

        fun run(tool: String, vararg a: Pair<String, String>): String =
            runBlocking { PluginTool(id, tool, registry, bridge, gate).execute(mapOf(*a)) }
    }

    private fun manifest(): PluginManifest = (ManifestParser.parse(text) as ParseResult.Ok).manifest

    private fun list(call: ServiceCall): List<String> = (call.extras[args] as ExtraValue.TextList).values

    @Test
    fun theBundledManifestParsesAndOnlyTargetsTermux() {
        val m = manifest()
        assertEquals(setOf("git_status", "git_diff", "git_log", "git_pull", "git_add", "git_commit", "git_push"), m.tools.map { it.name }.toSet())
        assertTrue(m.tools.all { it.action.packageName == "com.termux" })
        assertTrue(m.tools.all { t -> t.params.first { it.name == "repo" }.required })
    }

    @Test
    fun theReadOnlyToolsContainNoWritingGitCommand() {
        val write = Regex("\\b(add|commit|push|pull|reset|checkout|clean|rm|merge|rebase)\\b")
        for (name in listOf("git_status", "git_diff", "git_log")) {
            val script = (manifest().tools.first { it.name == name }.action.extras[args] as ExtraValue.TextList).values[1]
            assertFalse("$name: $script", write.containsMatchIn(script))
        }
    }

    @Test
    fun aCommitMessageTravelsAsAnArgumentNeverInsideTheShellString() {
        val rig = Rig(text, id)
        val evil = "x\"; touch /tmp/pwned; \""
        rig.run("git_commit", "repo" to "/data/data/com.termux/files/home/lily", "message" to evil)
        val call = rig.bridge.calls.single()
        val values = list(call)
        assertEquals("-c", values[0])
        assertFalse(values[1].contains("pwned"))
        assertEquals(evil, values[3])
        assertEquals(ExtraValue.Text("/data/data/com.termux/files/home/lily"), call.extras["com.termux.RUN_COMMAND_WORKDIR"])
    }

    @Test
    fun aPathTravelsAsAnArgumentAndALeftOutPathIsEmpty() {
        val rig = Rig(text, id)
        rig.run("git_diff", "repo" to "/r", "path" to "a b; rm -rf ~")
        assertEquals("a b; rm -rf ~", list(rig.bridge.calls.last())[3])
        assertFalse(list(rig.bridge.calls.last())[1].contains("rm -rf"))
        rig.run("git_diff", "repo" to "/r")
        assertEquals("", list(rig.bridge.calls.last())[3])
    }

    @Test
    fun theRepoFolderIsRequiredAndNothingRunsWithoutIt() {
        val rig = Rig(text, id)
        val out = rig.run("git_status")
        assertTrue(out, out.contains("'repo' is required"))
        assertTrue(rig.bridge.calls.isEmpty() && rig.gate.requests.isEmpty())
    }
}
