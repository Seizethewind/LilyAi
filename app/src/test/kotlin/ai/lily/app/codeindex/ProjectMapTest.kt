package ai.lily.app.codeindex

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProjectMapTest {
    @Test
    fun aKotlinFileListsItsTypesAndFunctionsInOrder() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put(
                "src/Parser.kt",
                listOf(
                    "package x",
                    "",
                    "class Parser {",
                    "    fun parseReply(text: String): List<Int> = emptyList()",
                    "    private fun helper() {}",
                    "}",
                    "",
                    "object Util",
                    "// class Commented",
                ).joinToString("\n") + "\n",
            )
        }

        assertEquals("src/Parser.kt: class Parser; fun parseReply; fun helper; object Util", ProjectMap(files).outline())
    }

    @Test
    fun otherLanguagesAreRecognisedAndNestedCodeIsIgnored() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("Main.java", "public class Main {\n    public static void main(String[] args) {}\n}\n")
            put("app.js", "export function render() {}\nexport const add = (a, b) => a + b\nclass Box {}\n")
            put("load.py", "def load():\n    pass\n\nclass Repo:\n    def save(self):\n        pass\n")
            put("main.go", "package main\n\nfunc (s *Server) Start() {}\n\ntype Config struct {\n}\n")
            put("run.rs", "pub fn run() {}\nstruct App;\n")
        }

        val expected = listOf(
            "Main.java: class Main",
            "app.js: function render; fn add; class Box",
            "load.py: def load; class Repo; def save",
            "main.go: func Start; struct Config",
            "run.rs: fn run; struct App",
        ).joinToString("\n")
        assertEquals(expected, ProjectMap(files).outline())
    }

    @Test
    fun unknownLanguagesAndFilesWithoutSymbolsAreLeftOut() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("notes.md", "class NotCode\n")
            put("consts.kt", "val x = 1\n")
        }

        assertEquals("", ProjectMap(files).outline())
    }

    @Test
    fun excludedFilesAreSkipped() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("A.kt", "fun a() {}\n")
            put("B.kt", "fun b() {}\n")
        }

        assertEquals("B.kt: fun b", ProjectMap(files).outline(exclude = setOf("A.kt")))
    }

    @Test
    fun theOutlineStopsAtTheBudgetAndSaysThereIsMore() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            for (i in 0..9) put("f$i.kt", "fun a$i() {}\n")
        }

        val out = ProjectMap(files).outline(maxChars = 40)

        assertEquals(listOf("f0.kt: fun a0", "f1.kt: fun a1", "(more files not shown)"), out.lines())
    }

    @Test
    fun aFileListsAtMostTheCapOfSymbols() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("Big.kt", (0 until 30).joinToString("\n") { "fun f$it() {}" } + "\n")
        }

        val line = ProjectMap(files).outline()

        assertEquals(ProjectMap.MAX_SYMBOLS_PER_FILE, line.split("; ").size)
    }

    @Test
    fun onlyChangedFilesAreReadAgain() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("A.kt", "fun a() {}\n")
            put("B.kt", "fun b() {}\n")
            put("notes.md", "text\n")
        }
        val map = ProjectMap(files)

        map.outline()
        assertEquals(listOf("A.kt", "B.kt"), files.reads.toList())

        map.outline()
        assertEquals(2, files.reads.size)

        files.put("B.kt", "fun b() {}\nfun c() {}\n", modified = 2L)
        assertEquals("A.kt: fun a\nB.kt: fun b; fun c", map.outline())
        assertEquals(listOf("A.kt", "B.kt", "B.kt"), files.reads.toList())
    }

    @Test
    fun aProjectThatCannotBeListedGivesAnEmptyOutlineNotAnError() = runBlocking<Unit> {
        val files = FakeCodeFiles().apply {
            put("A.kt", "fun a() {}\n")
            failure = IOException("disk gone")
        }

        assertTrue(WorkspaceIndex(files).outline().isEmpty())
    }
}
