package ai.lily.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KnowledgeUrlTest {
    private fun refused(raw: String): String {
        try {
            KnowledgeText.normaliseUrl(raw)
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        fail("should have been refused: $raw")
        return ""
    }

    @Test
    fun schemeAndHostAreLowerCasedAndFragmentIsDropped() {
        assertEquals("https://example.com/Page", KnowledgeText.normaliseUrl("HTTPS://Example.COM/Page#top"))
    }

    @Test
    fun aBareAddressHasNoTrailingSlash() {
        assertEquals("https://example.com", KnowledgeText.normaliseUrl("https://example.com/"))
        assertEquals("https://example.com", KnowledgeText.normaliseUrl("https://example.com"))
    }

    @Test
    fun pathQueryAndPortAreKept() {
        assertEquals("http://example.com:8080/a/b?q=1", KnowledgeText.normaliseUrl("  http://EXAMPLE.com:8080/a/b?q=1#frag  "))
        assertEquals("https://example.com/a/", KnowledgeText.normaliseUrl("https://example.com/a/"))
    }

    @Test
    fun onlyWebAddressesWithAHostAreAccepted() {
        assertTrue(refused("").contains("empty"))
        assertTrue(refused("ftp://example.com/file").contains("http"))
        assertTrue(refused("example.com").contains("http"))
        assertTrue(refused("not a url").contains("not a valid"))
        assertTrue(refused("https:///path").contains("no host"))
    }

    @Test
    fun credentialsInTheAddressAreRefused() {
        assertTrue(refused("https://user:secret@example.com/").contains("username and password"))
    }

    @Test
    fun hashIsStableAndDependsOnTheText() {
        assertEquals(KnowledgeText.hash("hello"), KnowledgeText.hash("hello"))
        assertNotEquals(KnowledgeText.hash("hello"), KnowledgeText.hash("hello!"))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", KnowledgeText.hash("hello"))
    }

    @Test
    fun charactersCountCodePointsLikeSqlite() {
        assertEquals(4L, KnowledgeText.characters("hi 😀"))
    }
}
