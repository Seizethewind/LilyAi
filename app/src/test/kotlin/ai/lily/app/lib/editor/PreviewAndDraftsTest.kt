package ai.lily.app.lib.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreviewAndDraftsTest {

    // ---- Markdown ----

    @Test
    fun headingsAndEmphasis() {
        val html = markdownToPreviewHtml("# Title\n\nSome **bold** text")
        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("<strong>bold</strong>"))
    }

    @Test
    fun htmlInMarkdownIsShownAsText() {
        val html = markdownToPreviewHtml("<script>alert(1)</script>")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun unsafeLinksAreMadeInert() {
        val html = markdownToPreviewHtml("[x](javascript:alert(1))")
        assertTrue(html.contains("href=\"#\""))
        val ok = markdownToPreviewHtml("[x](https://example.com)")
        assertTrue(ok.contains("href=\"https://example.com\""))
    }

    // ---- Preview pages ----

    @Test
    fun onlySomeFilesCanBePreviewed() {
        assertTrue(canPreview("index.HTML"))
        assertTrue(canPreview("a/b/readme.md"))
        assertFalse(canPreview("Main.kt"))
        assertEquals("text/css", previewMime("style.css"))
    }

    @Test
    fun preludeGoesInsideHeadWithoutNewLines() {
        val page = "<!doctype html>\n<html>\n<head>\n<title>x</title>\n</head>\n<body></body>\n</html>"
        val out = injectPrelude(page)
        assertTrue(out.contains("<head><script>"))
        assertEquals(page.lines().size, out.lines().size)
    }

    @Test
    fun preludeNeverComesBeforeTheDoctype() {
        val out = injectPrelude("<!doctype html><p>hi</p>")
        assertTrue(out.startsWith("<!doctype html><script>"))
    }

    @Test
    fun aFragmentGetsThePreludeAtTheStart() {
        assertTrue(injectPrelude("<p>hi</p>").startsWith("<script>"))
    }

    @Test
    fun svgAndMarkdownAndJsDocuments() {
        val svg = buildPreviewDocument("a.svg", "<svg/>")
        assertEquals("image/svg+xml", svg.mime)
        assertEquals("<svg/>", svg.html)

        val md = buildPreviewDocument("README.md", "# Hi")
        assertEquals("text/html", md.mime)
        assertTrue(md.html.contains("<h1>Hi</h1>"))

        val js = buildPreviewDocument("app.js", "console.log(1)\n// </script>")
        assertTrue(js.html.contains("console.log(1)"))
        assertTrue(js.html.contains("<\\/script"))
    }

    // ---- Drafts ----

    @Test
    fun aDraftRoundTrips() {
        val dir = Files.createTempDirectory("lilyai-drafts").toFile()
        try {
            val store = DraftStore(File(dir, "drafts"))
            store.save("src/a.kt", "hello\nworld", 42)
            val d = store.load("src/a.kt")
            assertNotNull(d)
            assertEquals(Draft("src/a.kt", 42, "hello\nworld"), d)
            assertNull(store.load("other.kt"))
            store.delete("src/a.kt")
            assertNull(store.load("src/a.kt"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pruneKeepsOnlyTheWantedDrafts() {
        val dir = Files.createTempDirectory("lilyai-drafts").toFile()
        try {
            val store = DraftStore(File(dir, "drafts"))
            store.save("a", "x", 1)
            store.save("b", "y", 2)
            store.pruneExcept(setOf("a"))
            assertNotNull(store.load("a"))
            assertNull(store.load("b"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- Settings ----

    @Test
    fun indentFollowsTheSetting() {
        assertEquals("  ", EditorSettings().indentFor("  "))
        assertEquals("    ", EditorSettings(tabSize = 4).indentFor("\t"))
        assertEquals("\t", EditorSettings(tabSize = -1).indentFor("  "))
    }

    @Test
    fun unknownLineSpacingFallsBackToNormal() {
        assertEquals(LineSpacing.NORMAL, LineSpacing.fromName("nope"))
        assertEquals(LineSpacing.COMPACT, LineSpacing.fromName("COMPACT"))
        assertEquals("Match file", tabSizeLabel(0))
        assertEquals("Tab", tabSizeLabel(-1))
        assertEquals("4 spaces", tabSizeLabel(4))
    }
}
