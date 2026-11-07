package ai.lily.app.discord

import ai.lily.app.plugins.ConfirmRequest
import ai.lily.app.plugins.ConfirmationGate
import ai.lily.app.plugins.HttpBridge
import ai.lily.app.plugins.HttpCall
import ai.lily.app.plugins.HttpMethod
import ai.lily.app.plugins.HttpReply
import ai.lily.app.plugins.InMemoryPluginSecrets
import ai.lily.app.plugins.InMemoryPluginStore
import ai.lily.app.plugins.PluginRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val TOKEN = "MTIzNDU2Nzg5.abcdef.0123456789abcdefghij"
private const val API = "http:discord.com/api/v10/"
private const val ID = "lily.discord"

private class FakeHttp : HttpBridge {
    val calls = ArrayList<HttpCall>()
    var reply: HttpReply = HttpReply.Done(200, "[]", false)
    var failure: Exception? = null

    override suspend fun call(call: HttpCall): HttpReply {
        calls += call
        failure?.let { throw it }
        return reply
    }
}

private class FakeGate(var answer: Boolean = true) : ConfirmationGate {
    val requests = ArrayList<ConfirmRequest>()

    override suspend fun confirm(request: ConfirmRequest): Boolean {
        requests += request
        return answer
    }
}

/** The real bundled Discord manifest, installed the way the app does it, with a fake network and a fake question. */
private class Rig(
    token: String? = TOKEN,
    install: Boolean = true,
    enabled: Boolean = true,
    grant: Boolean = true,
    answer: Boolean = true,
) {
    val store = InMemoryPluginStore()
    val registry = PluginRegistry(store)
    val http = FakeHttp()
    val gate = FakeGate(answer)
    val secrets = InMemoryPluginSecrets()
    var clock = 1_000L
    val api = DiscordApi(registry, secrets, http, gate) { clock }

    init {
        if (install) {
            registry.install(File("src/main/assets/plugins/discord.json").readText())
            registry.setEnabled(ID, enabled)
            if (grant) registry.grant(ID, API)
        }
        if (token != null) secrets.set(ID, "token", token)
    }

    fun auto(vararg tools: String) {
        for (tool in tools) registry.setAutoApprove(ID, tool, true)
    }

    fun reply(status: Int, body: String, truncated: Boolean = false) {
        http.reply = HttpReply.Done(status, body, truncated)
    }

    fun <T> ask(block: suspend DiscordApi.() -> T): T = runBlocking { api.block() }
}

class DiscordApiTest {
    private fun <T> ok(r: DiscordResult<T>): T = when (r) {
        is DiscordResult.Ok -> r.value
        is DiscordResult.Failed -> throw AssertionError("expected success, got: ${r.message}")
    }

    private fun failed(r: DiscordResult<*>): DiscordResult.Failed =
        r as? DiscordResult.Failed ?: throw AssertionError("expected a failure, got $r")

    private val messageJson =
        """{"id":"500","type":0,"content":"hi","timestamp":"2026-11-07T01:00:00.000000+00:00","edited_timestamp":null,"author":{"id":"9","username":"lilybot","bot":true},"attachments":[],"embeds":[]}"""

    @Test
    fun serversAreReadWithTheBotTokenAndStayOnThePinnedPath() {
        val rig = Rig()
        rig.auto("discord_list_channels")
        rig.reply(200, """[{"id":"2","name":"Zeta"},{"id":"1","name":"alpha"},{"id":"bad","name":"x"}]""")
        val guilds = ok(rig.ask { guilds() })
        assertEquals(listOf("alpha", "Zeta"), guilds.map { it.name })
        val call = rig.http.calls.single()
        assertEquals(HttpMethod.GET, call.method)
        assertEquals("https://discord.com/api/v10/users/@me/guilds?limit=200", call.url)
        assertEquals("Bot $TOKEN", call.headers["Authorization"])
        assertTrue(call.headers.getValue("User-Agent").startsWith("DiscordBot ("))
        assertEquals(DiscordApi.PERMISSION, call.permission)
        assertEquals(DiscordApi.MAX_ANSWER_BYTES, call.maxBytes)
        assertFalse(call.url.contains(TOKEN))
        assertTrue(rig.gate.requests.isEmpty())
    }

    @Test
    fun aPluginToolStillReadsOnly16KbUnlessACallAsksForMore() {
        val call = HttpCall(HttpMethod.GET, "https://discord.com/api/v10/x", emptyMap(), null, 1000L, DiscordApi.PERMISSION)
        assertEquals(HttpBridge.MAX_RESPONSE_BYTES, call.maxBytes)
        assertEquals(16 * 1024, call.maxBytes)
    }

    @Test
    fun channelsAreReadForOneServer() {
        val rig = Rig()
        rig.auto("discord_list_channels")
        rig.reply(200, """[{"id":"11","name":"chat","type":0,"position":1,"parent_id":"10"},{"id":"10","name":"HUB","type":4,"position":0,"parent_id":null}]""")
        val channels = ok(rig.ask { channels("987654321") })
        assertEquals(2, channels.size)
        assertEquals("https://discord.com/api/v10/guilds/987654321/channels", rig.http.calls.single().url)
    }

    @Test
    fun messagesComeBackOldestFirstWhateverOrderDiscordUsed() {
        val rig = Rig()
        rig.auto("discord_read_messages")
        rig.reply(
            200,
            """[
              {"id":"1002","type":0,"content":"second","timestamp":"2026-11-07T01:00:02.000000+00:00","edited_timestamp":null,"author":{"id":"5","username":"bob","global_name":"Bob","bot":false},"attachments":[{"filename":"a.png"}],"embeds":[{},{}]},
              {"id":"999","type":0,"content":"first","timestamp":"2026-11-07T01:00:01.000000+00:00","edited_timestamp":"2026-11-07T01:05:00.000000+00:00","author":{"id":"6","username":"lily","global_name":null,"bot":true},"attachments":[],"embeds":[]}
            ]""",
        )
        val messages = ok(rig.ask { messages("123456") })
        assertEquals(listOf("999", "1002"), messages.map { it.id })
        val first = messages[0]
        assertEquals("lily", first.authorName)
        assertTrue(first.authorIsBot)
        assertEquals("2026-11-07T01:05:00.000000+00:00", first.editedTimestamp)
        val second = messages[1]
        assertEquals("Bob", second.authorName)
        assertEquals(listOf("a.png"), second.attachments)
        assertEquals(2, second.embedCount)
        assertNull(second.editedTimestamp)
        assertEquals("https://discord.com/api/v10/channels/123456/messages?limit=50", rig.http.calls.single().url)
    }

    @Test
    fun pollingAsksForTheMessagesAfterOneAndScrollingBackForTheOnesBefore() {
        val rig = Rig()
        rig.auto("discord_read_messages")
        ok(rig.ask { messages("123456", limit = 30, after = "1002") })
        ok(rig.ask { messages("123456", limit = 500, before = "999") })
        ok(rig.ask { messages("123456", limit = 0) })
        assertEquals("https://discord.com/api/v10/channels/123456/messages?limit=30&after=1002", rig.http.calls[0].url)
        assertEquals("https://discord.com/api/v10/channels/123456/messages?limit=100&before=999", rig.http.calls[1].url)
        assertEquals("https://discord.com/api/v10/channels/123456/messages?limit=1", rig.http.calls[2].url)
    }

    @Test
    fun anIdCannotAddAPathPartOrAQueryAndNothingIsSent() {
        val rig = Rig()
        rig.auto("discord_list_channels", "discord_read_messages")
        failed(rig.ask { channels("1/users") })
        failed(rig.ask { channels("") })
        failed(rig.ask { messages("abc") })
        failed(rig.ask { messages("1?limit=1") })
        failed(rig.ask { messages("123", after = "x") })
        failed(rig.ask { messages("123", before = "1&after=2") })
        failed(rig.ask { messages("123", after = "2", before = "3") })
        failed(rig.ask { send("1/messages", "hi") })
        assertTrue(rig.http.calls.isEmpty())
        assertTrue(rig.gate.requests.isEmpty())
    }

    @Test
    fun theMostBasicSetupProblemsAreSaidInPlainWordsAndNothingIsSent() {
        val cases = listOf(
            Rig(install = false) to "not installed",
            Rig(enabled = false) to "turned off",
            Rig(grant = false) to "Allow the Discord plugin",
            Rig(token = null) to "bot token",
        )
        for ((rig, words) in cases) {
            val r = failed(rig.ask { guilds() })
            assertTrue(r.message, r.needsSetup && r.message.contains(words))
            assertTrue(rig.http.calls.isEmpty() && rig.gate.requests.isEmpty())
        }
    }

    @Test
    fun turningThePluginOffOrTakingThePermissionBackStopsItAtOnce() {
        val rig = Rig()
        rig.auto("discord_list_channels")
        ok(rig.ask { guilds() })
        rig.registry.revoke(ID, API)
        assertTrue(failed(rig.ask { guilds() }).needsSetup)
        rig.registry.grant(ID, API)
        rig.registry.setEnabled(ID, false)
        assertTrue(failed(rig.ask { guilds() }).needsSetup)
        assertEquals(1, rig.http.calls.size)
    }

    @Test
    fun aReadAsksUnlessThePersonSwitchedDontAskAgainOnForThatTool() {
        val rig = Rig()
        rig.auto("discord_read_messages")
        ok(rig.ask { messages("123") })
        assertTrue(rig.gate.requests.isEmpty())
        ok(rig.ask { guilds() })
        val request = rig.gate.requests.single()
        assertEquals("discord_list_channels", request.toolName)
        assertTrue(request.isHttp)
        assertFalse(request.changes)
        assertEquals("discord.com/api/v10/", request.targetPackage)
        assertEquals("GET", request.details.first { it.first == "Method" }.second)
        assertEquals("Bot [secret:token]", request.details.first { it.first == "Header Authorization" }.second)
        assertTrue(request.details.none { it.second.contains(TOKEN) })
        assertEquals(2, rig.http.calls.size)
    }

    @Test
    fun nothingIsSentWhenThePersonSaysNo() {
        val rig = Rig(answer = false)
        val r = failed(rig.ask { messages("123") })
        assertTrue(r.denied)
        assertTrue(rig.http.calls.isEmpty())
    }

    @Test
    fun discordAskingUsToSlowDownIsHonouredWithoutHammeringIt() {
        val rig = Rig()
        rig.auto("discord_read_messages")
        rig.reply(429, """{"message":"You are being rate limited.","retry_after":2.5,"global":false}""")
        val first = failed(rig.ask { messages("123") })
        assertEquals(2500L, first.retryAfterMs)
        val second = failed(rig.ask { messages("123") })
        assertEquals(2500L, second.retryAfterMs)
        assertEquals(1, rig.http.calls.size)
        rig.clock += 2_600
        rig.reply(200, "[]")
        assertTrue(ok(rig.ask { messages("123") }).isEmpty())
        assertEquals(2, rig.http.calls.size)
    }

    @Test
    fun theWaitDiscordAsksForIsKeptWithinSensibleBounds() {
        val tiny = Rig()
        tiny.auto("discord_read_messages")
        tiny.reply(429, """{"retry_after":0}""")
        assertEquals(1_000L, failed(tiny.ask { messages("123") }).retryAfterMs)
        val huge = Rig()
        huge.auto("discord_read_messages")
        huge.reply(429, """{"retry_after":99999}""")
        assertEquals(60_000L, failed(huge.ask { messages("123") }).retryAfterMs)
        val unknown = Rig()
        unknown.auto("discord_read_messages")
        unknown.reply(429, "not json")
        assertEquals(5_000L, failed(unknown.ask { messages("123") }).retryAfterMs)
    }

    @Test
    fun errorsAreExplainedAndTheTokenIsRemovedFromThem() {
        val rig = Rig()
        rig.auto("discord_read_messages")
        rig.reply(401, """{"message":"401: Unauthorized for $TOKEN"}""")
        val unauthorized = failed(rig.ask { messages("123") })
        assertTrue(unauthorized.message, unauthorized.needsSetup && unauthorized.message.contains("401"))
        assertFalse(unauthorized.message.contains(TOKEN))
        assertTrue(unauthorized.message.contains("[secret]"))

        rig.reply(403, """{"message":"Missing Access","code":50001}""")
        val forbidden = failed(rig.ask { messages("123") })
        assertTrue(forbidden.message, forbidden.message.contains("403") && forbidden.message.contains("Missing Access") && !forbidden.needsSetup)

        rig.reply(404, """{"message":"Unknown Channel"}""")
        assertTrue(failed(rig.ask { messages("123") }).message.contains("404"))

        rig.reply(500, "oops")
        assertTrue(failed(rig.ask { messages("123") }).message.contains("http 500"))
    }

    @Test
    fun aTooLongOrBrokenAnswerIsAFailureNotACrash() {
        val rig = Rig()
        rig.auto("discord_read_messages")
        rig.reply(200, "[{\"id\":\"1\"", truncated = true)
        assertTrue(failed(rig.ask { messages("123") }).message.contains("too long"))
        rig.reply(200, "this is not json")
        assertTrue(failed(rig.ask { messages("123") }).message.contains("not what this app expects"))
        rig.reply(200, """{"an":"object"}""")
        assertTrue(failed(rig.ask { messages("123") }).message.contains("not what this app expects"))
    }

    @Test
    fun networkFailuresAreFailuresAndNeverShowTheToken() {
        val rig = Rig()
        rig.auto("discord_read_messages")
        rig.http.reply = HttpReply.Failed(ai.lily.app.plugins.FailReason.TIMEOUT, "no answer in time")
        assertTrue(failed(rig.ask { messages("123") }).message.contains("no answer in time"))
        rig.http.failure = IllegalStateException("could not reach https://x/?k=$TOKEN")
        val r = failed(rig.ask { messages("123") })
        assertTrue(r.message, r.message.contains("Could not reach Discord"))
        assertFalse(r.message.contains(TOKEN))
    }

    @Test
    fun sendingAlwaysAsksEvenIfDontAskAgainWasForcedIntoTheFile() {
        val rig = Rig()
        val plugin = rig.registry.get(ID)!!
        rig.store.put(plugin.copy(state = plugin.state.copy(autoApprove = setOf("discord_send_message"))))
        rig.reply(200, messageJson)
        val sent = ok(rig.ask { send("123456", "hi") })
        assertEquals("500", sent.id)
        assertTrue(sent.authorIsBot)
        ok(rig.ask { send("123456", "hi again") })
        assertEquals(2, rig.gate.requests.size)
        val request = rig.gate.requests.first()
        assertTrue(request.isHttp && request.changes)
        assertEquals("discord_send_message", request.toolName)
        assertEquals("POST", request.details.first { it.first == "Method" }.second)
        assertEquals("hi", request.details.first { it.first == "Message" }.second)
        assertTrue(request.details.none { it.second.contains(TOKEN) })
    }

    @Test
    fun aMessageIsRealJsonAndCannotPingAnyone() {
        val rig = Rig()
        rig.reply(200, messageJson)
        val evil = "hi @everyone\",\"tts\":true,\"x\":\""
        ok(rig.ask { send("123456", evil) })
        val call = rig.http.calls.single()
        assertEquals(HttpMethod.POST, call.method)
        assertEquals("https://discord.com/api/v10/channels/123456/messages", call.url)
        val json = JSONObject(call.body!!)
        assertEquals(evil, json.getString("content"))
        assertFalse(json.has("tts"))
        assertEquals(0, json.getJSONObject("allowed_mentions").getJSONArray("parse").length())
        assertEquals(2, json.length())
    }

    @Test
    fun aMessageThatIsNotAllowedIsRefusedBeforeAskingOrSending() {
        val rig = Rig()
        assertEquals("Write something first.", failed(rig.ask { send("123456", "   ") }).message)
        assertTrue(failed(rig.ask { send("123456", "x".repeat(2001)) }).message.contains("2000"))
        failed(rig.ask { send("123456", "a\u0000b") })
        assertTrue(rig.gate.requests.isEmpty() && rig.http.calls.isEmpty())
        rig.reply(200, messageJson)
        ok(rig.ask { send("123456", "x".repeat(2000)) })
        assertEquals(1, rig.http.calls.size)
    }

    @Test
    fun nothingIsPostedWhenThePersonSaysNo() {
        val rig = Rig(answer = false)
        val r = failed(rig.ask { send("123456", "hello") })
        assertTrue(r.denied)
        assertTrue(rig.http.calls.isEmpty())
    }

    @Test
    fun onlyReadsAndOnePostAreEverSent() {
        val rig = Rig()
        rig.auto("discord_list_channels", "discord_read_messages")
        rig.reply(200, messageJson)
        ok(rig.ask { send("123456", "hi") })
        rig.reply(200, "[]")
        ok(rig.ask { guilds() })
        ok(rig.ask { channels("123") })
        ok(rig.ask { messages("123") })
        assertEquals(listOf(HttpMethod.POST, HttpMethod.GET, HttpMethod.GET, HttpMethod.GET), rig.http.calls.map { it.method })
        assertTrue(rig.http.calls.all { it.url.startsWith("https://discord.com/api/v10/") })
    }
}
