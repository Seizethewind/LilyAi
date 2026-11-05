package ai.lily.app.settings

import ai.lily.app.core.WorkerType
import ai.lily.app.gateway.RouterConfig
import ai.lily.app.gateway.RouterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsCodecTest {
    private fun read(vararg saved: Pair<String, String>) = SettingsCodec.read(saved.toMap().let { m -> { k: String -> m[k].orEmpty() } })

    @Test
    fun `nothing saved gives the defaults`() {
        val s = read()
        assertEquals(RouterConfig(), s.router)
        assertNull(s.router2)
        assertEquals(emptyList<Any>(), s.workers)
        assertEquals(LilyProfile(), s.lily)
        assertNull(s.journalDb)
        assertNull(s.embedding)
    }

    @Test
    fun `settings saved before names, kinds, icons and Lily's profile still load`() {
        val oldWorkers = """[{"id":"a","name":"Tester 1","role":"TEST","router":1,"model":"m","instructions":"i"}]"""
        val s = read(
            "router_url" to "http://box:3001/v1",
            "router_key" to "k",
            "router2_url" to "http://box:20128/v1",
            "workers_json" to oldWorkers,
            "journal_url" to "libsql://j",
            "journal_token" to "t",
        )
        assertEquals("", s.router.name)
        assertEquals(RouterKind.FreeLlmApi, s.router.kind)
        assertEquals(RouterKind.NineRouter, s.router2?.kind)
        assertEquals("k", s.router.apiKey)
        assertEquals(LilyProfile(), s.lily)
        val w = s.workers.single()
        assertEquals(WorkerType.TEST, w.role)
        assertEquals("", w.iconKey)
        assertEquals("", w.colorKey)
        assertEquals("libsql://j", s.journalDb?.url)
    }

    @Test
    fun `an embedding the endpoint refuses counts as not set`() {
        assertNull(read("embed_url" to "http://box/v1", "embed_model" to "auto").embedding)
    }

    @Test
    fun `a broken worker list loads as no workers`() {
        assertEquals(emptyList<Any>(), read("workers_json" to "not json").workers)
    }
}
