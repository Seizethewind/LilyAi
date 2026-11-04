package ai.lily.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterEndpointsTest {
    // These counts come from reading both routers' route files. Update them, and the tables, when a router gets a new route.
    @Test
    fun `every route of both routers is listed`() {
        assertEquals(225, FreeLlmApiEndpoints.all.size)
        assertEquals(270, NineRouterEndpoints.all.size)
    }

    @Test
    fun `each table lists a route once and every path starts at the root`() {
        for (kind in RouterKind.entries) {
            val all = kind.endpoints
            assertEquals("${kind.label} has a duplicate route", all.size, all.map { it.method to it.path }.toSet().size)
            assertTrue(all.all { it.path.startsWith("/") && it.method.isNotBlank() && it.group.isNotBlank() })
        }
    }

    @Test
    fun `the calls the app makes today are in the lists`() {
        for (kind in RouterKind.entries) {
            assertTrue(kind.endpoints.any { it.method == "POST" && it.path.endsWith("/v1/chat/completions") })
            assertTrue(kind.endpoints.any { it.method == "GET" && it.path.endsWith("/v1/models") })
            assertTrue(kind.endpoints.any { it.method == "POST" && it.path.endsWith("/v1/embeddings") })
        }
    }

    @Test
    fun `the health address of each kind is listed and needs no key`() {
        for (kind in RouterKind.entries) {
            val health = kind.endpoints.single { it.method == "GET" && it.path == kind.healthPath }
            assertEquals(EndpointAuth.Open, health.auth)
        }
    }

    @Test
    fun `the url token copies keep the token placeholder and use the token as the key`() {
        val copies = FreeLlmApiEndpoints.all.filter { it.group == "URL token (v1/t/:token)" }
        assertEquals(25, copies.size)
        assertTrue(copies.all { it.path.startsWith("/v1/t/:token/") && it.auth == EndpointAuth.UrlToken })
    }

    @Test
    fun `the origin drops a trailing v1 and slashes`() {
        assertEquals("http://h:3001", RouterConfig(baseUrl = "http://h:3001/v1").origin)
        assertEquals("http://h:3001", RouterConfig(baseUrl = " http://h:3001/v1/ ").origin)
        assertEquals("https://r.example/base", RouterConfig(baseUrl = "https://r.example/base").origin)
    }

    @Test
    fun `an endpoint address is the origin plus the path`() {
        val config = RouterConfig(baseUrl = "http://h:3001/v1")
        val chat = RouterKind.FreeLlmApi.endpoints.first { it.method == "POST" && it.path == "/v1/chat/completions" }
        assertEquals("http://h:3001/v1/chat/completions", chat.url(config))
    }

    @Test
    fun `old saved settings without a kind or name still load`() {
        assertEquals(RouterKind.FreeLlmApi, RouterKind.fromId("", RouterKind.FreeLlmApi))
        assertEquals(RouterKind.NineRouter, RouterKind.fromId(null, RouterKind.NineRouter))
        assertEquals(RouterKind.NineRouter, RouterKind.fromId("9router", RouterKind.FreeLlmApi))
        assertEquals(RouterKind.FreeLlmApi, RouterKind.fromId("something-new", RouterKind.FreeLlmApi))
        assertEquals("FreeLLMAPI", RouterConfig().title)
        assertEquals("Home", RouterConfig(name = "Home").title)
    }

    @Test
    fun `the search finds by word in any order`() {
        val chat = RouterKind.FreeLlmApi.endpoints.first { it.method == "POST" && it.path == "/v1/chat/completions" }
        assertTrue(chat.matches(""))
        assertTrue(chat.matches("post chat"))
        assertTrue(chat.matches("completions   POST"))
        assertFalse(chat.matches("delete chat"))
    }

    @Test
    fun `a malformed table line is rejected`() {
        assertEquals(1, EndpointTable.parse("# G\nGET /x O").size)
        var failed = false
        try {
            EndpointTable.parse("GET /x")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
