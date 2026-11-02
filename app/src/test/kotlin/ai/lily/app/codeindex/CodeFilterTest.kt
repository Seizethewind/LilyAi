package ai.lily.app.codeindex

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeFilterTest {
    @Test
    fun sourceAndTextFilesAreAccepted() {
        listOf("src/Main.kt", "README", "Makefile", ".gitignore", "docs/notes.md", "a/b/c/index.html", "dist").forEach {
            assertTrue(it, CodeFilter.acceptsPath(it))
        }
    }

    @Test
    fun dependenciesAndBuildOutputAreSkipped() {
        listOf("node_modules/x/index.js", "app/build/out.txt", ".git/config", "a/dist/bundle.js", "venv/lib/x.py").forEach {
            assertFalse(it, CodeFilter.acceptsPath(it))
        }
    }

    @Test
    fun binariesLockFilesAndMinifiedCodeAreSkipped() {
        listOf("a/logo.PNG", "fonts/x.woff2", "lib/app.jar", "package-lock.json", "web/app.min.js", "web/app.min.css", "icons/x.svg", "a.js.map")
            .forEach { assertFalse(it, CodeFilter.acceptsPath(it)) }
    }

    @Test
    fun sizeLimitsAreInclusiveAtTheTopAndExcludeEmptyFiles() {
        assertFalse(CodeFilter.acceptsSize(0))
        assertTrue(CodeFilter.acceptsSize(1))
        assertTrue(CodeFilter.acceptsSize(200_000))
        assertFalse(CodeFilter.acceptsSize(200_001))
    }

    @Test
    fun binaryAndMinifiedTextLooksGenerated() {
        assertFalse(CodeFilter.looksGenerated("a\nb\n"))
        assertFalse(CodeFilter.looksGenerated("x".repeat(2000)))
        assertTrue(CodeFilter.looksGenerated("x".repeat(2001)))
        assertTrue(CodeFilter.looksGenerated("ab\u0000cd"))
        // Many long-ish lines are fine: the limit is per line.
        assertFalse(CodeFilter.looksGenerated(("y".repeat(1500) + "\n").repeat(10)))
    }
}
