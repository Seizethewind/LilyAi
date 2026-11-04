package ai.lily.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginCatalogTest {
    private fun entry(id: String = "my.plugin", url: String = "https://example.com/m.json") =
        """{"id":"$id","name":"My plugin","version":"1.0.0","description":"d","manifest":"$url"}"""

    private fun catalog(vararg entries: String) = """{"schema":1,"plugins":[${entries.joinToString(",")}]}"""

    @Test
    fun aGoodEntryIsListed() {
        assertEquals(listOf("my.plugin"), PluginCatalog.parse(catalog(entry())).map { it.id })
    }

    @Test
    fun entriesWithAPlainHttpAddressOrABadIdAreLeftOut() {
        val text = catalog(entry(url = "http://example.com/m.json"), entry(id = "BAD ID"), entry(id = "ok.one"))
        assertEquals(listOf("ok.one"), PluginCatalog.parse(text).map { it.id })
    }

    @Test
    fun aFileThatIsNoCatalogIsAnError() {
        assertThrows(IllegalArgumentException::class.java) { PluginCatalog.parse("""{"schema":2,"plugins":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { PluginCatalog.parse("nope") }
    }

    @Test
    fun aSelectionThatLooksLikeAPlaceholderStaysLiteral() {
        assertEquals("a.kt: {file}", fillPrompt("{file}: {selection}", "{file}", "a.kt", "kt"))
    }
}
