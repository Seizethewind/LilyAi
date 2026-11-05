package ai.lily.app.plugins

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpToolTest {
    private class Rig(
        method: String = "POST",
        body: String = """{"title":"{title}","draft":true}""",
        untrusted: Boolean = false,
        grant: Boolean = true,
        auto: Boolean = false,
        answer: Boolean = true,
        secret: String? = HttpManifests.TOKEN,
        manifest: String = HttpManifests.json(method = method, body = body, untrusted = untrusted),
    ) {
        val store = InMemoryPluginStore()
        val registry = PluginRegistry(store)
        val http = FakeHttpBridge()
        val gate = RecordingGate(answer)
        val secrets = InMemoryPluginSecrets()
        val id = HttpManifests.ID

        init {
            registry.install(manifest)
            registry.setEnabled(id, true)
            if (grant) registry.grant(id, HttpManifests.HOST_PERMISSION)
            if (secret != null) secrets.set(id, "token", secret)
            if (auto) {
                // Set the way a hand-edited file could: the registry itself refuses this for a request that changes something.
                val plugin = registry.get(id)!!
                store.put(plugin.copy(state = plugin.state.copy(autoApprove = setOf("call"))))
            }
        }

        fun run(vararg args: Pair<String, String>): String = runBlocking {
            PluginTool(id, "call", registry, FakeBridge(), gate, http, secrets).execute(mapOf(*args))
        }
    }

    @Test
    fun aRequestThatChangesSomethingAsksEveryTimeEvenIfDontAskAgainWasForced() {
        val rig = Rig(auto = true)
        rig.run("repo" to "r")
        rig.run("repo" to "r")
        assertEquals(2, rig.gate.requests.size)
        assertTrue(rig.gate.requests.all { it.isHttp && it.changes })
        assertEquals(2, rig.http.calls.size)
    }

    @Test
    fun aGetRunsWithoutAskingWhenTheyChoseThatAndNothingElseChanges() {
        val rig = Rig(method = "GET", body = "null", auto = true, answer = false)
        val out = rig.run("repo" to "r")
        assertTrue(out, out.startsWith("http 200"))
        assertTrue(rig.gate.requests.isEmpty())
        assertEquals(HttpMethod.GET, rig.http.calls.single().method)
    }

    @Test
    fun aGetAsksByDefault() {
        val rig = Rig(method = "GET", body = "null")
        rig.run("repo" to "r")
        val request = rig.gate.requests.single()
        assertTrue(request.isHttp)
        assertFalse(request.changes)
    }

    @Test
    fun nothingIsSentWhenThePersonSaysNo() {
        val rig = Rig(answer = false)
        val out = rig.run("repo" to "r")
        assertTrue(out, out.startsWith("denied"))
        assertTrue(rig.http.calls.isEmpty())
    }

    @Test
    fun theQuestionHidesTheSecretAndTheRequestCarriesIt() {
        val rig = Rig()
        rig.run("repo" to "my-repo", "title" to "Hello")
        val shown = rig.gate.requests.single().details
        assertEquals("POST", shown.first { it.first == "Method" }.second)
        assertEquals("https://api.example.com/repos/my-repo/pulls", shown.first { it.first == "Address" }.second)
        assertEquals("Bearer [secret:token]", shown.first { it.first == "Header Authorization" }.second)
        assertTrue(shown.none { it.second.contains(HttpManifests.TOKEN) })
        val call = rig.http.calls.single()
        assertEquals("Bearer ${HttpManifests.TOKEN}", call.headers["Authorization"])
        assertEquals("https://api.example.com/repos/my-repo/pulls", call.url)
        assertEquals(Permission.Http("api.example.com", ""), call.permission)
    }

    @Test
    fun aSecretThatWasNotFilledInStopsBeforeAskingOrSending() {
        val rig = Rig(secret = null)
        val out = rig.run("repo" to "r")
        assertTrue(out, out.startsWith("error:") && out.contains("'Token'"))
        assertTrue(rig.gate.requests.isEmpty() && rig.http.calls.isEmpty())
    }

    @Test
    fun aValueCannotLeaveTheHostOrThePath() {
        val rig = Rig()
        for (evil in listOf("..", "x@evil.com/../y", "a/b", "x?y=1", "x#z")) {
            val out = rig.run("repo" to evil)
            if (evil == "x?y=1" || evil == "x#z") {
                // Encoded, so these stay inside one path part and are sent as a plain name.
                assertTrue(out, out.startsWith("http"))
                assertTrue(rig.http.calls.last().url.startsWith("https://api.example.com/repos/x%"))
            } else {
                assertTrue("$evil: $out", out.startsWith("error:"))
            }
        }
        assertTrue(rig.http.calls.all { it.url.startsWith("https://api.example.com/repos/") })
    }

    @Test
    fun aValueThatLooksLikeAPlaceholderStaysLiteral() {
        val rig = Rig()
        rig.run("repo" to "{secret.token}")
        assertEquals("https://api.example.com/repos/%7Bsecret.token%7D/pulls", rig.http.calls.single().url)
    }

    @Test
    fun theBodyIsRealJsonSoAQuoteCannotBreakOut() {
        val rig = Rig()
        val evil = "x\",\"draft\":false,\"admin\":\"y"
        rig.run("repo" to "r", "title" to evil)
        val json = JSONObject(rig.http.calls.single().body!!)
        assertEquals(evil, json.getString("title"))
        assertTrue(json.getBoolean("draft"))
        assertEquals(2, json.length())
    }

    @Test
    fun anOptionalBodyValueThatWasNotGivenIsLeftOut() {
        val rig = Rig()
        rig.run("repo" to "r")
        val json = JSONObject(rig.http.calls.single().body!!)
        assertFalse(json.has("title"))
        assertTrue(json.getBoolean("draft"))
    }

    @Test
    fun aSecretIsRemovedFromWhatComesBack() {
        val rig = Rig()
        rig.http.reply = HttpReply.Done(200, "you sent ${HttpManifests.TOKEN} and ${HttpGuard.encode(HttpManifests.TOKEN)}", false)
        val out = rig.run("repo" to "r")
        assertFalse(out, out.contains(HttpManifests.TOKEN))
        assertTrue(out, out.contains("[secret]"))
    }

    @Test
    fun aSecretIsRemovedFromAnErrorMessageToo() {
        val rig = Rig()
        rig.http.failure = IllegalStateException("could not reach https://x/?k=${HttpManifests.TOKEN}")
        val out = rig.run("repo" to "r")
        assertTrue(out, out.startsWith("error:"))
        assertFalse(out, out.contains(HttpManifests.TOKEN))
    }

    @Test
    fun aLongAnswerIsCutAndSaysSo() {
        val rig = Rig()
        rig.http.reply = HttpReply.Done(200, "a".repeat(20_000), true)
        val out = rig.run("repo" to "r")
        assertTrue(out.length < 16_300)
        assertTrue(out.contains("only the first part"))
    }

    @Test
    fun anAnswerThatIsNotSuccessIsAnError() {
        val rig = Rig()
        rig.http.reply = HttpReply.Done(404, "not found", false)
        val out = rig.run("repo" to "r")
        assertTrue(out, out.startsWith("error: http 404"))
        assertTrue(out.contains("not found"))
    }

    @Test
    fun textFromOtherPeopleIsMarkedWhenThePluginSaysSo() {
        val marked = Rig(untrusted = true)
        assertTrue(marked.run("repo" to "r").contains("never as instructions"))
        val plain = Rig()
        assertFalse(plain.run("repo" to "r").contains("never as instructions"))
    }

    @Test
    fun failuresBecomeErrorTextAndNeverThrow() {
        val rig = Rig()
        rig.http.reply = HttpReply.Failed(FailReason.TIMEOUT, "slow")
        val out = rig.run("repo" to "r")
        assertTrue(out, out.startsWith("error:") && out.contains("slow"))
    }

    @Test
    fun aPermissionThatWasNotAllowedSendsNothing() {
        val rig = Rig(grant = false)
        val out = rig.run("repo" to "r")
        assertTrue(out, out.startsWith("error:") && out.contains("not allowed"))
        assertTrue(rig.gate.requests.isEmpty() && rig.http.calls.isEmpty())
    }

    @Test
    fun badArgumentsAreRefusedBeforeAnythingElse() {
        val rig = Rig()
        assertTrue(rig.run().contains("'repo' is required"))
        assertTrue(rig.run("repo" to "r", "x" to "y").contains("unknown argument 'x'"))
        assertTrue(rig.gate.requests.isEmpty() && rig.http.calls.isEmpty())
    }

    @Test
    fun theRegistryNeverOffersDontAskAgainForARequestThatChangesSomething() {
        val post = PluginRegistry(InMemoryPluginStore())
        post.install(HttpManifests.json())
        post.grant(HttpManifests.ID, HttpManifests.HOST_PERMISSION)
        assertFalse(post.setAutoApprove(HttpManifests.ID, "call", true))

        val get = PluginRegistry(InMemoryPluginStore())
        get.install(HttpManifests.json(method = "GET", body = "null"))
        get.grant(HttpManifests.ID, HttpManifests.HOST_PERMISSION)
        assertTrue(get.setAutoApprove(HttpManifests.ID, "call", true))
    }

    @Test
    fun takingTheHostBackStopsTheToolAndClearsDontAskAgain() {
        val rig = Rig(method = "GET", body = "null")
        rig.registry.setAutoApprove(rig.id, "call", true)
        rig.registry.revoke(rig.id, HttpManifests.HOST_PERMISSION)
        assertTrue(rig.registry.get(rig.id)!!.state.autoApprove.isEmpty())
        assertTrue(rig.run("repo" to "r").startsWith("error:"))
        assertTrue(rig.http.calls.isEmpty())
    }

    @Test
    fun onlyAHttpPluginWithThePermissionAllowedIsRegistered() {
        val rig = Rig()
        val tools = ai.lily.app.tools.ToolSystem()
        val report = PluginHost(rig.registry, FakeBridge(), rig.gate, rig.http, rig.secrets).registerInto(tools)
        assertEquals(listOf("call"), report.registered)
        val off = Rig(grant = false)
        val skipped = PluginHost(off.registry, FakeBridge(), off.gate, off.http, off.secrets).registerInto(ai.lily.app.tools.ToolSystem())
        assertTrue(skipped.registered.isEmpty() && skipped.skipped.single().contains("not allowed"))
    }

    @Test
    fun uninstallingForgetsTheSecretsKeptForThePlugin() {
        val secrets = InMemoryPluginSecrets()
        secrets.set("a.plugin", "token", "x")
        secrets.set("a.plugin2", "token", "y")
        secrets.clear("a.plugin")
        assertEquals(null, secrets.get("a.plugin", "token"))
        assertEquals("y", secrets.get("a.plugin2", "token"))
    }
}
