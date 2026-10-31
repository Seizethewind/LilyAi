package ai.lily.app.lib.editor

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NavigationLogicTest {

    // ---- Fuzzy matching ----

    @Test
    fun lettersMustAppearInOrder() {
        assertNotNull(fuzzyScore("mnact", "MainActivity.kt"))
        assertNull(fuzzyScore("tcamn", "MainActivity"))
        assertNull(fuzzyScore("abc", "ab"))
    }

    @Test
    fun caseAndSpacesInTheQueryDoNotMatter() {
        assertNotNull(fuzzyScore("MAIN act", "mainactivity"))
        assertEquals(0, fuzzyScore("", "anything"))
    }

    @Test
    fun touchingLettersBeatScatteredOnes() {
        val tight = fuzzyScore("main", "main.kt")!!
        val loose = fuzzyScore("main", "mxaxixn.kt")!!
        assertTrue(tight > loose)
    }

    @Test
    fun aFileNameMatchRanksFirst() {
        val paths = listOf("src/domain.kt", "src/Main.kt", "docs/readme.md")
        val ranked = fuzzyRank("main", paths) { fuzzyPathScore("main", it) }
        assertEquals("src/Main.kt", ranked.first())
        assertFalse("docs/readme.md" in ranked)
    }

    @Test
    fun anEmptyQueryKeepsTheOrderAndTheLimit() {
        val items = listOf("c", "a", "b")
        assertEquals(listOf("c", "a"), fuzzyRank("", items, 2) { null })
    }

    // ---- Searching text ----

    @Test
    fun hitsHaveLineColumnAndPreview() {
        val text = "alpha\n  beta gamma\nGamma"
        val hits = searchText(text, buildFindRegex(FindOptions("gamma")))
        assertEquals(2, hits.size)
        assertEquals(2, hits[0].line)
        assertEquals(8, hits[0].column)
        assertEquals("beta gamma", hits[0].preview)
        assertEquals(5, hits[0].previewStart)
        assertEquals(5, hits[0].length)
        assertEquals(3, hits[1].line)
        assertEquals(1, hits[1].column)
        assertEquals("Gamma", hits[1].preview)
    }

    @Test
    fun aSearchStopsAtTheHitLimit() {
        val hits = searchText("a\n".repeat(10), Regex("a"), maxHits = 3)
        assertEquals(3, hits.size)
        assertEquals(listOf(1, 2, 3), hits.map { it.line })
    }

    @Test
    fun aVeryLongLineIsCutAroundTheMatch() {
        val text = "x".repeat(300) + "needle" + "y".repeat(300)
        val hit = searchText(text, Regex("needle")).single()
        assertTrue(hit.preview.length <= 160)
        assertEquals("needle", hit.preview.substring(hit.previewStart, hit.previewStart + hit.length))
        assertEquals(301, hit.column)
    }

    @Test
    fun noMatchesGivesNoHits() {
        assertTrue(searchText("nothing here", Regex("zzz")).isEmpty())
    }

    // ---- Searching a folder ----

    @Test
    fun folderSearchSkipsBuildDirsAndPrefersUnsavedText() {
        val dir = Files.createTempDirectory("lilyai-ws").toFile()
        try {
            File(dir, "a.txt").writeText("hello world")
            File(dir, "sub").mkdirs()
            File(dir, "sub/b.txt").writeText("nothing")
            File(dir, "build").mkdirs()
            File(dir, "build/c.txt").writeText("hello")
            val result = runBlocking {
                searchFiles(dir, FindOptions("hello"), mapOf("sub/b.txt" to "Hello again"))
            }
            assertEquals(listOf("a.txt", "sub/b.txt"), result.files.map { it.path }.sorted())
            assertEquals(2, result.hitCount)
            assertFalse(result.truncated)
        } finally {
            dir.deleteRecursively()
        }
    }
}
