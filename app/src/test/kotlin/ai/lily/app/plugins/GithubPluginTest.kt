package ai.lily.app.plugins

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the bundled GitHub manifest: it parses, it can only read and open pull requests, and the token never leaks. */
class GithubPluginTest {
    private val text = File("src/main/assets/plugins/github.json").readText()
    private val id = "lily.github"
    private val host = "http:api.github.com"
    private val token = "github_pat_0123456789abcdef"

    private fun manifest(): PluginManifest = (ManifestParser.parse(text) as ParseResult.Ok).manifest

    private class Rig(text: String, id: String, token: String) {
        val registry = PluginRegistry(InMemoryPluginStore())
        val http = FakeHttpBridge()
        val gate = RecordingGate(true)
        val secrets = InMemoryPluginSecrets()
        val id = id

        init {
            registry.install(text)
            registry.setEnabled(id, true)
            registry.grant(id, "http:api.github.com")
            secrets.set(id, "token", token)
        }

        fun run(tool: String, vararg a: Pair<String, String>): String =
            runBlocking { PluginTool(id, tool, registry, FakeBridge(), gate, http, secrets).execute(mapOf(*a)) }
    }

    @Test
    fun theBundledManifestParsesAndOnlyTalksToGithub() {
        val m = manifest()
        assertEquals(listOf<Permission>(Permission.Http("api.github.com", "")), m.permissions)
        assertEquals(setOf("gh_list_prs", "gh_get_pr", "gh_pr_checks", "gh_create_pr"), m.tools.map { it.name }.toSet())
        assertTrue(m.tools.all { (it.action as HttpAction).url.startsWith("https://api.github.com/repos/") })
        val setting = m.settings.single()
        assertTrue(setting.secret && setting.required)
    }

    @Test
    fun onlyCreatingAPullRequestChangesAnythingAndItAsksEveryTime() {
        val actions = manifest().tools.associate { it.name to it.action as HttpAction }
        assertEquals(setOf("gh_create_pr"), actions.filterValues { it.method != HttpMethod.GET }.keys)
        assertEquals(HttpMethod.POST, actions.getValue("gh_create_pr").method)
        assertTrue(actions.getValue("gh_create_pr").alwaysAsks)
        assertTrue(actions.values.none { it.url.contains("/merge") })
    }

    @Test
    fun everyAnswerIsMarkedAsTextWrittenByOthers() {
        assertTrue(manifest().tools.all { (it.action as HttpAction).untrusted })
    }

    @Test
    fun aListIsAGetWithTheTokenInTheHeaderOnly() {
        val rig = Rig(text, id, token)
        val out = rig.run("gh_list_prs", "owner" to "Seizethewind", "repo" to "LilyAi")
        assertTrue(out, out.startsWith("http 200"))
        val call = rig.http.calls.single()
        assertEquals(HttpMethod.GET, call.method)
        assertEquals("https://api.github.com/repos/Seizethewind/LilyAi/pulls?state=open&per_page=5", call.url)
        assertEquals("Bearer $token", call.headers["Authorization"])
        assertFalse(call.url.contains(token))
    }

    @Test
    fun aBranchNameCannotStandInForTheCommitSha() {
        val rig = Rig(text, id, token)
        val out = rig.run("gh_pr_checks", "owner" to "o", "repo" to "r", "sha" to "feat/x")
        assertTrue(out, out.startsWith("error:"))
        assertTrue(rig.http.calls.isEmpty())
    }

    @Test
    fun aTitleWithQuotesStaysInsideItsJsonFieldAndALeftOutBodyIsDropped() {
        val rig = Rig(text, id, token)
        val evil = "x\",\"base\":\"evil"
        rig.run("gh_create_pr", "owner" to "o", "repo" to "r", "title" to evil, "head" to "feat/a", "base" to "main")
        val json = JSONObject(rig.http.calls.single().body!!)
        assertEquals(evil, json.getString("title"))
        assertEquals("main", json.getString("base"))
        assertFalse(json.has("body"))
        assertEquals(1, rig.gate.requests.size)
        assertTrue(rig.gate.requests.single().changes)
    }

    @Test
    fun theTokenIsHiddenInTheQuestionAndRemovedFromTheAnswer() {
        val rig = Rig(text, id, token)
        rig.http.reply = HttpReply.Done(200, "echo $token", false)
        val out = rig.run("gh_get_pr", "owner" to "o", "repo" to "r", "number" to "7")
        assertFalse(out, out.contains(token))
        assertTrue(rig.gate.requests.single().details.none { it.second.contains(token) })
    }
}
