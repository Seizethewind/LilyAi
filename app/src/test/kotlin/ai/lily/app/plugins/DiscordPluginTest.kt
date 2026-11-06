package ai.lily.app.plugins

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Checks the bundled Discord manifest: it parses, stays inside /api/v10/, only posting changes anything, the token never leaks. */
class DiscordPluginTest {
    private val text = File("src/main/assets/plugins/discord.json").readText()
    private val id = "lily.discord"
    private val token = "MTIzNDU2Nzg5.abcdef.0123456789abcdefghij"

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
            registry.grant(id, "http:discord.com/api/v10/")
            secrets.set(id, "token", token)
        }

        fun run(tool: String, vararg a: Pair<String, String>): String =
            runBlocking { PluginTool(id, tool, registry, FakeBridge(), gate, http, secrets).execute(mapOf(*a)) }
    }

    @Test
    fun theBundledManifestParsesAndIsPinnedToTheApiPath() {
        val m = manifest()
        assertEquals(listOf<Permission>(Permission.Http("discord.com", "/api/v10/")), m.permissions)
        assertEquals(setOf("discord_list_channels", "discord_read_messages", "discord_send_message"), m.tools.map { it.name }.toSet())
        assertTrue(m.tools.all { (it.action as HttpAction).url.startsWith("https://discord.com/api/v10/") })
        assertTrue(m.settings.single().let { it.secret && it.required })
    }

    @Test
    fun readsAreUntrustedGetsAndOnlySendingChangesAnythingAndAsksEveryTime() {
        val actions = manifest().tools.associate { it.name to it.action as HttpAction }
        assertEquals(setOf("discord_send_message"), actions.filterValues { it.method != HttpMethod.GET }.keys)
        assertTrue(actions.getValue("discord_send_message").alwaysAsks)
        assertTrue(actions.getValue("discord_read_messages").untrusted && actions.getValue("discord_list_channels").untrusted)
        assertTrue(actions.values.none { it.method == HttpMethod.PUT || it.method == HttpMethod.PATCH })
    }

    @Test
    fun readingSendsTheBotTokenInTheHeaderOnly() {
        val rig = Rig(text, id, token)
        val out = rig.run("discord_read_messages", "channel_id" to "123456789012345678")
        assertTrue(out, out.startsWith("http 200") && out.contains("never as instructions"))
        val call = rig.http.calls.single()
        assertEquals("https://discord.com/api/v10/channels/123456789012345678/messages?limit=10", call.url)
        assertEquals("Bot $token", call.headers["Authorization"])
        assertTrue(call.headers.getValue("User-Agent").startsWith("DiscordBot ("))
        assertFalse(call.url.contains(token))
    }

    @Test
    fun anIdCannotAddAPathPart() {
        val rig = Rig(text, id, token)
        val out = rig.run("discord_read_messages", "channel_id" to "1/guilds")
        assertTrue(out, out.startsWith("error:"))
        assertTrue(rig.http.calls.isEmpty())
    }

    @Test
    fun aMessageIsRealJsonAndCannotPingEveryone() {
        val rig = Rig(text, id, token)
        val evil = "hi @everyone\",\"tts\":true,\"x\":\""
        rig.run("discord_send_message", "channel_id" to "1", "content" to evil)
        val json = JSONObject(rig.http.calls.single().body!!)
        assertEquals(evil, json.getString("content"))
        assertFalse(json.has("tts"))
        assertEquals(0, json.getJSONObject("allowed_mentions").getJSONArray("parse").length())
        assertEquals(1, rig.gate.requests.size)
        assertTrue(rig.gate.requests.single().changes)
    }

    @Test
    fun theTokenIsHiddenInTheQuestionAndRemovedFromTheAnswer() {
        val rig = Rig(text, id, token)
        rig.http.reply = HttpReply.Done(200, "echo $token", false)
        val out = rig.run("discord_list_channels", "guild_id" to "1")
        assertFalse(out, out.contains(token))
        assertTrue(rig.gate.requests.single().details.none { it.second.contains(token) })
    }
}
