package ai.lily.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginListingTest {
    private fun text(id: String) = TestManifests.json(id = id)

    private fun manifest(id: String) = (ManifestParser.parse(text(id)) as ParseResult.Ok).manifest

    private fun entry(id: String, extra: String = "") =
        """{"id":"$id","name":"Name","version":"1.0.0","description":"d","manifest":"https://example.com/$id.json"$extra}"""

    private fun catalog(vararg entries: String) = PluginCatalog.parse("""{"schema":1,"plugins":[${entries.joinToString(",")}]}""")

    private fun listing(name: String = "Discord", icon: String = "", description: String = "Chat with a server", author: String = "Someone") =
        Listing("a.b", name, "1.0.0", description, author, icon, "", null, null, false)

    @Test
    fun eachIdAppearsOnceWithBundledFirst() {
        val handAdded = InstalledPlugin(manifest("hand.one"), text("hand.one"), PluginState())
        val bundledTwo = InstalledPlugin(manifest("ship.one"), text("ship.one"), PluginState())
        val all = mergeListings(
            installed = listOf(handAdded, bundledTwo),
            bundled = listOf(manifest("ship.one") to text("ship.one")),
            catalog = catalog(entry("ship.one"), entry("cat.one")),
        )
        assertEquals(listOf("ship.one", "cat.one", "hand.one"), all.map { it.id })
        assertEquals(listOf(true, false, true), all.map { it.installed })
        assertNotNull(all[0].bundledText)
        assertNull(all[0].manifestUrl)
        assertEquals("https://example.com/cat.one.json", all[1].manifestUrl)
        assertTrue(all[2].bundledText == null && all[2].manifestUrl == null)
    }

    @Test
    fun searchLooksAtNameDescriptionAuthorAndIgnoresCase() {
        val l = listing()
        assertTrue(l.matches(""))
        assertTrue(l.matches("  "))
        assertTrue(l.matches("DISC"))
        assertTrue(l.matches("a server"))
        assertTrue(l.matches("someone"))
        assertFalse(l.matches("zzz"))
    }

    @Test
    fun theTileShowsTheIconOrTheFirstLetter() {
        assertEquals("D", listing().tile)
        assertEquals("\uD83D\uDC19", listing(icon = "\uD83D\uDC19").tile)
        assertEquals("\uD83D\uDC19", listing(name = "\uD83D\uDC19 Octo").tile)
    }

    @Test
    fun aCatalogEntryKeepsItsLooksAndDropsBadOnes() {
        val good = catalog(entry("good.one", ",\"author\":\"Me\",\"icon\":\"GH\",\"accent\":\"#112233\"")).single()
        assertEquals(listOf("Me", "GH", "#112233"), listOf(good.author, good.icon, good.accent))
        val bad = catalog(entry("bad.one", ",\"icon\":\"abc\",\"accent\":\"red\"")).single()
        assertEquals(listOf("", ""), listOf(bad.icon, bad.accent))
    }
}
