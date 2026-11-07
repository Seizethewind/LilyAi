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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val PLUGIN = "lily.discord"
private const val CDN_KEY = "http:cdn.discordapp.com/avatars/"
private const val API_KEY = "http:discord.com/api/v10/"
private const val SECRET = "MTIzNDU2Nzg5.abcdef.0123456789abcdefghij"

private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)

private class PicHttp : HttpBridge {
    val calls = ArrayList<HttpCall>()
    var reply: HttpReply = HttpReply.Done(200, "", false, PNG)

    override suspend fun call(call: HttpCall): HttpReply {
        calls += call
        return reply
    }
}

private class PicGate : ConfirmationGate {
    val requests = ArrayList<ConfirmRequest>()

    override suspend fun confirm(request: ConfirmRequest): Boolean {
        requests += request
        return true
    }
}

class DiscordLiveAvatarTest {
    private class Rig(allowPictures: Boolean = true, enabled: Boolean = true) {
        val registry = PluginRegistry(InMemoryPluginStore())
        val http = PicHttp()
        val gate = PicGate()
        val secrets = InMemoryPluginSecrets()
        val api = DiscordApi(registry, secrets, http, gate)

        init {
            registry.install(File("src/main/assets/plugins/discord.json").readText())
            registry.setEnabled(PLUGIN, enabled)
            registry.grant(PLUGIN, API_KEY)
            if (allowPictures) registry.grant(PLUGIN, CDN_KEY)
            secrets.set(PLUGIN, "token", SECRET)
        }
    }

    private fun <T> ask(block: suspend () -> T): T = runBlocking { block() }

    private fun message(id: String, author: String = "5") =
        DiscordMessage(id, author, "someone", false, "x", "t", null, emptyList(), 0, 0)

    // ---- live chat ----

    @Test
    fun pollingIsFastThenSlowsDownAndNeverIgnoresDiscord() {
        assertEquals(LiveChat.FAST_MS, LiveChat.delayMs(0, null))
        assertEquals(LiveChat.FAST_MS, LiveChat.delayMs(LiveChat.IDLE_AFTER - 1, null))
        assertEquals(LiveChat.SLOW_MS, LiveChat.delayMs(LiveChat.IDLE_AFTER, null))
        assertEquals(20_000L, LiveChat.delayMs(0, 20_000L))
        assertEquals(LiveChat.SLOW_MS, LiveChat.delayMs(50, 1_000L))
    }

    @Test
    fun messagesByTheSamePersonCloseTogetherShareOneHeader() {
        // Ids made from times: ((ms - epoch) shl 22). 2026-11-07 is around 1_794_000_000_000 ms.
        fun id(ms: Long): String = ((ms - 1_420_070_400_000L) shl 22).toString()
        val t = 1_794_000_000_000L
        val a = message(id(t))
        val b = message(id(t + 60_000))
        val late = message(id(t + MessageLayout.GROUP_WINDOW_MS + 1_000))
        val other = message(id(t + 61_000), author = "6")
        assertTrue(MessageLayout.startsGroup(null, a))
        assertFalse(MessageLayout.startsGroup(a, b))
        assertTrue(MessageLayout.startsGroup(a, late))
        assertTrue(MessageLayout.startsGroup(b, other))
        assertTrue(MessageLayout.startsGroup(a, b.copy(type = 7)))
    }

    // ---- ids and avatars ----

    @Test
    fun anIdCarriesItsCreationTime() {
        // Discord's documented example: 175928847299117063 was created 2016-04-30 11:18:25.796 UTC.
        assertEquals(1_462_015_105_796L, Snowflake.createdAtMillis("175928847299117063"))
        assertNull(Snowflake.createdAtMillis("abc"))
        assertNull(Snowflake.createdAtMillis("0"))
    }

    @Test
    fun onlyRealAvatarHashesAreAcceptedAndAddressesAreBuiltFromThem() {
        assertTrue(AvatarId.isValidHash("8342729096ea3675442027381ff50dfe"))
        assertTrue(AvatarId.isValidHash("a_8342729096ea3675442027381ff50dfe"))
        for (bad in listOf("", "../x", "a/b", "x?y=1", "A", "a b", "a".repeat(65))) assertFalse("'$bad'", AvatarId.isValidHash(bad))
        assertEquals("https://cdn.discordapp.com/avatars/123/abc.png?size=64", AvatarId.url("123", "abc", 64))
        assertEquals("123/abc/64", AvatarId.key("123", "abc", 64))
    }

    @Test
    fun onlyPngBytesPassForAPicture() {
        assertTrue(AvatarId.looksLikePng(PNG))
        assertFalse(AvatarId.looksLikePng("<html>no</html>".toByteArray()))
        assertFalse(AvatarId.looksLikePng(ByteArray(0)))
    }

    @Test
    fun theParserKeepsTheAvatarAndAccountNameButDropsABadHash() {
        val good = DiscordParse.messages(
            """[{"id":"9","type":0,"content":"hi","author":{"id":"5","username":"bob","global_name":"Bobby","avatar":"abc123"}}]""",
        ).single()
        assertEquals("abc123", good.authorAvatar)
        assertEquals("bob", good.authorUsername)
        assertEquals("Bobby", good.authorName)
        val bad = DiscordParse.messages("""[{"id":"9","author":{"id":"5","username":"bob","avatar":"../../x"}}]""").single()
        assertNull(bad.authorAvatar)
        val none = DiscordParse.messages("""[{"id":"9","author":{"id":"5","username":"bob","avatar":null}}]""").single()
        assertNull(none.authorAvatar)
    }

    @Test
    fun anAvatarComesFromTheCdnWithNoTokenAndNoQuestion() {
        val rig = Rig()
        val result = ask { rig.api.avatar("123456", "abc123", 64) }
        assertTrue(result is DiscordResult.Ok)
        val call = rig.http.calls.single()
        assertEquals(HttpMethod.GET, call.method)
        assertEquals("https://cdn.discordapp.com/avatars/123456/abc123.png?size=64", call.url)
        assertEquals(DiscordApi.AVATAR_PERMISSION, call.permission)
        assertTrue(call.binary)
        assertEquals(DiscordApi.MAX_AVATAR_BYTES, call.maxBytes)
        assertTrue(call.headers.keys.none { it.equals("Authorization", ignoreCase = true) })
        assertTrue(call.headers.values.none { it.contains(SECRET) })
        assertTrue(rig.gate.requests.isEmpty())
    }

    @Test
    fun picturesStayOffUntilThePersonAllowsTheCdn() {
        val rig = Rig(allowPictures = false)
        assertFalse(rig.api.avatarsAllowed())
        val r = ask { rig.api.avatar("123456", "abc123") } as DiscordResult.Failed
        assertTrue(r.message, r.needsSetup && r.message.contains("cdn.discordapp.com"))
        assertTrue(rig.http.calls.isEmpty())
        rig.registry.grant(PLUGIN, CDN_KEY)
        assertTrue(rig.api.avatarsAllowed())
        rig.registry.setEnabled(PLUGIN, false)
        assertFalse(rig.api.avatarsAllowed())
    }

    @Test
    fun aBadIdHashSizeOrAnswerIsRefusedWithoutAPictureEscaping() {
        val rig = Rig()
        for (call in listOf("x" to "abc", "123" to "../abc", "123" to "a/b")) {
            assertTrue(ask { rig.api.avatar(call.first, call.second) } is DiscordResult.Failed)
        }
        assertTrue(ask { rig.api.avatar("123", "abc", 7) } is DiscordResult.Failed)
        assertTrue(rig.http.calls.isEmpty())
        rig.http.reply = HttpReply.Done(200, "", false, "<html>".toByteArray())
        assertTrue(ask { rig.api.avatar("123", "abc") } is DiscordResult.Failed)
        rig.http.reply = HttpReply.Done(404, "", false, PNG)
        assertTrue(ask { rig.api.avatar("123", "abc") } is DiscordResult.Failed)
        rig.http.reply = HttpReply.Done(200, "", true, PNG)
        assertTrue(ask { rig.api.avatar("123", "abc") } is DiscordResult.Failed)
    }

    @Test
    fun theBundledManifestAsksForBothPermissionsAndNamesTheScreen() {
        val text = File("src/main/assets/plugins/discord.json").readText()
        assertTrue(text.contains("\"http:discord.com/api/v10/\""))
        assertTrue(text.contains("\"http:cdn.discordapp.com/avatars/\""))
        assertTrue(text.contains("\"screen\": \"discord\""))
        assertTrue(text.contains("\"version\": \"1.2.0\""))
    }
}
