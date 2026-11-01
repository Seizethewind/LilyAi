package ai.lily.app.lib.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditBlocksTest {

    private fun block(path: String, search: String, replace: String) =
        "$path\n<<<<<<< SEARCH\n$search\n=======\n$replace\n>>>>>>> REPLACE\n"

    // ---- Reading a reply ----

    @Test
    fun aBlockWithItsPathAndSomeProse() {
        val parsed = parseReply("I renamed it.\n\n" + block("src/a.kt", "fun a() = 1", "fun a() = 2"))
        assertEquals(listOf(FileEdit("src/a.kt", "fun a() = 1", "fun a() = 2", false, false)), parsed.edits)
        assertEquals("I renamed it.", parsed.prose)
    }

    @Test
    fun decoratedPathsAndCodeFencesAreUnderstood() {
        val reply = "**`src/a.kt`**\n```kotlin\n<<<<<<< SEARCH\nx\n=======\ny\n>>>>>>> REPLACE\n```\n" +
            "File: b.py\n<<<<<<< SEARCH\n1\n=======\n2\n>>>>>>> REPLACE"
        val edits = parseReply(reply).edits
        assertEquals(listOf("src/a.kt", "b.py"), edits.map { it.path })
    }

    @Test
    fun oneEmptySearchMeansANewFileAndOneEmptyReplaceMeansDelete() {
        val create = parseReply("new.py\n<<<<<<< SEARCH\n=======\nprint(1)\n>>>>>>> REPLACE").edits.single()
        assertTrue(create.searchEmpty)
        assertFalse(create.replaceEmpty)
        assertEquals("print(1)", create.replace)
        val delete = parseReply("a.py\n<<<<<<< SEARCH\nold\n=======\n>>>>>>> REPLACE").edits.single()
        assertTrue(delete.replaceEmpty)
    }

    @Test
    fun aBlockThatIsNeverClosedIsIgnored() {
        assertTrue(parseReply("a.kt\n<<<<<<< SEARCH\nx\n=======\ny\n").edits.isEmpty())
        assertTrue(parseReply("just an answer").edits.isEmpty())
    }

    @Test
    fun severalBlocksForOneFileShareItsPath() {
        val reply = "a.kt\n<<<<<<< SEARCH\n1\n=======\n2\n>>>>>>> REPLACE\n<<<<<<< SEARCH\n3\n=======\n4\n>>>>>>> REPLACE\n"
        assertEquals(listOf("a.kt", "a.kt"), parseReply(reply).edits.map { it.path })
    }

    @Test
    fun pathsMustStayInsideTheWorkspace() {
        assertTrue(isSafePath("src/a.kt"))
        assertFalse(isSafePath("../a.kt"))
        assertFalse(isSafePath("/etc/passwd"))
        assertFalse(isSafePath("C:/x.txt"))
        assertFalse(isSafePath(""))
    }

    // ---- Applying ----

    private fun edit(search: String, replace: String) =
        FileEdit("f", search, replace, search.isEmpty(), replace.isEmpty())

    @Test
    fun anExactMatchIsReplaced() {
        val out = applyFileEdits("a\nb\nc\n", listOf(edit("b", "B")))
        assertEquals("a\nB\nc\n", out.text)
        assertTrue(out.errors.isEmpty())
    }

    @Test
    fun aMatchInSeveralPlacesIsRefused() {
        val out = applyFileEdits("x\nx\n", listOf(edit("x", "y")))
        assertEquals("x\nx\n", out.text)
        assertEquals(1, out.errors.size)
    }

    @Test
    fun aMissingMatchIsReported() {
        val out = applyFileEdits("abc", listOf(edit("zzz", "y")))
        assertEquals("abc", out.text)
        assertEquals(1, out.errors.size)
    }

    @Test
    fun trailingSpacesInTheFileDoNotStopAMatch() {
        val out = applyFileEdits("if (a) {  \n  b\n}\n", listOf(edit("if (a) {\n  b", "if (a) {\n  c")))
        assertTrue(out.errors.isEmpty())
        assertEquals("if (a) {\n  c\n}\n", out.text)
    }

    @Test
    fun deletingALeavesNoEmptyLine() {
        val out = applyFileEdits("a\nb\nc\n", listOf(edit("b", "")))
        assertEquals("a\nc\n", out.text)
    }

    @Test
    fun anEmptySearchOnlyWorksOnAnEmptyFile() {
        assertEquals("hello", applyFileEdits("", listOf(edit("", "hello"))).text)
        val refused = applyFileEdits("keep me", listOf(edit("", "hello")))
        assertEquals("keep me", refused.text)
        assertEquals(1, refused.errors.size)
    }

    @Test
    fun oneBadBlockMeansNothingIsApplied() {
        val out = applyFileEdits("a\nb\n", listOf(edit("a", "A"), edit("zzz", "Z")))
        assertEquals("a\nb\n", out.text)
        assertEquals(1, out.errors.size)
    }

    // ---- Diff ----

    @Test
    fun aChangedLineIsRemovedAndAdded() {
        val diff = lineDiff("a\nb", "a\nc")
        assertEquals(
            listOf(
                DiffLine(DiffKind.SAME, "a"),
                DiffLine(DiffKind.DEL, "b"),
                DiffLine(DiffKind.ADD, "c"),
            ),
            diff,
        )
    }

    @Test
    fun anEmptySideIsAllAddedOrAllRemoved() {
        assertEquals(listOf(DiffLine(DiffKind.ADD, "x")), lineDiff("", "x"))
        assertEquals(listOf(DiffLine(DiffKind.DEL, "x")), lineDiff("x", ""))
    }

    @Test
    fun foldingKeepsContextAroundChangesOnly() {
        val old = (1..21).joinToString("\n") { "line $it" }
        val new = old.replace("line 11", "changed")
        val folded = foldDiff(lineDiff(old, new), context = 1)
        assertEquals(1, folded.count { it.kind == DiffKind.ADD })
        assertEquals(1, folded.count { it.kind == DiffKind.DEL })
        assertEquals(2, folded.count { it.kind == DiffKind.SKIP })
        assertTrue(folded.size < 10)
    }

    // ---- The prompt ----

    @Test
    fun thePromptShowsTheFilesAndMarksTheActiveOne() {
        val prompt = buildEditPrompt(
            "rename a",
            listOf(PromptFile("a.kt", "fun a() = 1\n", active = true)),
            listOf("a.kt", "b.kt"),
        )
        assertTrue(prompt.contains("rename a"))
        assertTrue(prompt.contains("b.kt"))
        assertTrue(prompt.contains("=== a.kt (the file the user is looking at) ==="))
        assertTrue(prompt.contains("fun a() = 1"))
    }

    @Test
    fun aFileTooBigForTheBudgetIsLeftOutAndNamed() {
        val big = "x".repeat(EDIT_PROMPT_BUDGET + 1)
        val prompt = buildEditPrompt("r", listOf(PromptFile("big.txt", big)), listOf("big.txt"))
        assertTrue(prompt.contains("big.txt === (left out"))
        assertFalse(prompt.contains(big))
    }
}
