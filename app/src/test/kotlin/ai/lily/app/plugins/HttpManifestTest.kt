package ai.lily.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpManifestTest {
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
    fun aValidHttpManifestIsReadFully() {
        val m = ok(HttpManifests.json())
        assertEquals(listOf<Permission>(Permission.Http("api.example.com", "")), m.permissions)
        val setting = m.settings.single()
        assertEquals("token", setting.name)
        assertTrue(setting.secret && setting.required)
        val action = m.tools.single().action as HttpAction
        assertEquals(HttpMethod.POST, action.method)
        assertEquals("https://api.example.com/repos/{repo}/pulls", action.url)
        assertEquals("Bearer {secret.token}", action.headers["Authorization"])
        assertEquals(BodyNode.Obj(mapOf("title" to BodyNode.Text("{title}"), "draft" to BodyNode.Flag(true))), action.body)
        assertEquals(Permission.Http("api.example.com", ""), action.permission)
        assertTrue(action.alwaysAsks)
        assertEquals("http:api.example.com", action.permissionKey)
    }

    @Test
    fun aGetRequestReadsAndMayBeSetToRunWithoutAsking() {
        val action = ok(HttpManifests.json(method = "GET", body = "null")).tools.single().action as HttpAction
        assertEquals(HttpMethod.GET, action.method)
        assertFalse(action.alwaysAsks)
    }

    @Test
    fun aPinnedPathIsKeptAndTheAddressMustStayInsideIt() {
        val perm = "\"http:discord.com/api/v10/\""
        val good = ok(HttpManifests.json(permissions = perm, url = "https://discord.com/api/v10/channels/{repo}/messages"))
        assertEquals(Permission.Http("discord.com", "/api/v10/"), (good.tools.single().action as HttpAction).permission)
        assertMentions(errors(HttpManifests.json(permissions = perm, url = "https://discord.com/api/v9/x")), "pinned path")
        assertMentions(errors(HttpManifests.json(permissions = perm, url = "https://discord.com/api/v{repo}/x")), "pinned path")
    }

    @Test
    fun theAddressMustBeHttpsOnAHostThePluginAsksFor() {
        assertMentions(errors(HttpManifests.json(url = "http://api.example.com/x")), "https://")
        assertMentions(errors(HttpManifests.json(url = "https://evil.com/x")), "does not ask for http:evil.com")
        assertMentions(errors(HttpManifests.json(url = "https://api.example.com.evil.com/x")), "does not ask for")
        assertMentions(errors(HttpManifests.json(url = "https://{repo}.example.com/x")), "host cannot contain")
        assertMentions(errors(HttpManifests.json(url = "https://api.example.com@evil.com/x")), "does not ask for")
    }

    @Test
    fun badHostPermissionsAreRejected() {
        for (host in listOf("127.0.0.1", "localhost", "api.example.com:8443", "*.example.com", "router.local")) {
            assertMentions(errors(HttpManifests.json(permissions = "\"http:$host\"", url = "https://api.example.com/x")), "permissions[0]")
        }
    }

    @Test
    fun onlyKnownMethodsAreAllowed() {
        assertMentions(errors(HttpManifests.json(method = "DELETE")), "method must be")
        assertMentions(errors(HttpManifests.json(method = "HEAD")), "method must be")
        ok(HttpManifests.json(method = "PUT"))
        ok(HttpManifests.json(method = "PATCH"))
    }

    @Test
    fun aGetRequestCannotCarryABody() {
        assertMentions(errors(HttpManifests.json(method = "GET")), "cannot have a body")
    }

    @Test
    fun placeholdersMustNameSomethingThatExists() {
        assertMentions(errors(HttpManifests.json(url = "https://api.example.com/{oops}")), "{oops}")
        assertMentions(errors(HttpManifests.json(headers = """{"Authorization":"Bearer {secret.nope}"}""")), "secret.nope")
        assertMentions(errors(HttpManifests.json(body = """{"a":"{secret.nope}"}""")), "secret.nope")
        assertMentions(errors(HttpManifests.json(headers = """{"X-Repo":"{repo}"}""")), "a header can only use")
        assertMentions(errors(HttpManifests.json(headers = """{"Authorization":"Bearer {setting.token}"}""")), "no plain setting")
        val plain = """[{"name":"token","label":"Token","secret":false}]"""
        assertMentions(errors(HttpManifests.json(settings = plain)), "no secret setting")
    }

    @Test
    fun aBraceThatIsNotAPlaceholderIsAnErrorInTheAddressAndHeadersButNotTheBody() {
        assertMentions(errors(HttpManifests.json(url = "https://api.example.com/{Repo}")), "not a placeholder")
        assertMentions(errors(HttpManifests.json(headers = """{"X":"{Bad}"}""")), "not a placeholder")
        ok(HttpManifests.json(body = """{"title":"fun x() { }"}"""))
    }

    @Test
    fun headersThatTheAppOwnsOrThatCouldSplitALineAreRejected() {
        assertMentions(errors(HttpManifests.json(headers = """{"Host":"evil.com"}""")), "Host")
        assertMentions(errors(HttpManifests.json(headers = """{"Content-Length":"1"}""")), "Content-Length")
        assertMentions(errors(HttpManifests.json(headers = """{"X":"a\r\nb: c"}""")), "control")
        assertMentions(errors(HttpManifests.json(headers = """{"X":"a","x":"b"}""")), "listed twice")
    }

    @Test
    fun theBodyIsAJsonTreeWithLimits() {
        assertMentions(errors(HttpManifests.json(body = "\"text\"")), "JSON object or list")
        assertMentions(errors(HttpManifests.json(body = """{"a":1.5}""")), "whole number")
        assertMentions(errors(HttpManifests.json(body = """{"a":null}""")), "must be text")
        assertMentions(errors(HttpManifests.json(body = """{"a":{"b":{"c":{"d":{"e":{"f":"x"}}}}}}""")), "nested more than")
        assertMentions(errors(HttpManifests.json(body = """{"bad key":"x"}""")), "not allowed")
        val many = (1..101).joinToString(",", "[", "]") { "1" }
        assertMentions(errors(HttpManifests.json(body = many)), "more than 100")
    }

    @Test
    fun settingsAreChecked() {
        assertMentions(errors(HttpManifests.json(settings = """[{"name":"Bad Name","label":"x"}]""")), "name must be")
        assertMentions(errors(HttpManifests.json(settings = """[{"name":"token"}]""")), "label")
        assertMentions(errors(HttpManifests.json(settings = """[{"name":"token","label":"T"},{"name":"token","label":"U"}]""")), "listed twice")
        assertMentions(errors(HttpManifests.json(settings = """[{"name":"token","label":"T","secret":"yes"}]""")), "true or false")
        assertMentions(errors(HttpManifests.json(settings = """[{"name":"token","label":"T","maxLength":99999}]""")), "maxLength")
        val m = ok(HttpManifests.json(settings = """[{"name":"token","label":"T","secret":true,"required":false,"maxLength":50}]"""))
        assertFalse(m.settings.single().required)
        assertEquals(50, m.settings.single().maxLength)
    }

    @Test
    fun aValueForASettingIsOneLine() {
        val spec = SettingSpec("t", "T", "", secret = true, required = true, maxLength = 10)
        assertEquals(null, spec.problemWith("abc"))
        assertTrue(spec.problemWith("  ") != null)
        assertTrue(spec.problemWith("x".repeat(11)) != null)
        assertTrue(spec.problemWith("a\nb") != null)
        assertTrue(spec.problemWith("a‮b") != null)
    }
}
