package ai.lily.app.workers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitWorkerTest {
    @Test
    fun aPlainToolLineGivesTheToolAndNoArguments() {
        assertEquals("git_status" to emptyMap<String, String>(), parseGitCall("tool: git_status"))
    }

    @Test
    fun aCommitMessageKeepsItsColonsAndTheRepoComesThrough() {
        val call = parseGitCall("tool: git_commit\nrepo: /r\nmessage: fix: handle empty input")
        assertEquals("git_commit" to mapOf("repo" to "/r", "message" to "fix: handle empty input"), call)
    }

    @Test
    fun argumentsTheToolDoesNotTakeAreDropped() {
        val call = parseGitCall("tool: git_push\nmessage: x\npath: y")
        assertEquals("git_push" to emptyMap<String, String>(), call)
    }

    @Test
    fun aCodeFenceAndUpperCaseAreTolerated() {
        assertEquals("git_diff" to mapOf("path" to "a.kt"), parseGitCall("```\nTOOL: Git_Diff\nPath: a.kt\n```"))
    }

    @Test
    fun noneProseAndUnknownToolsGiveNull() {
        assertNull(parseGitCall("NONE"))
        assertNull(parseGitCall("Sure, I will commit that for you."))
        assertNull(parseGitCall("tool: git_reset\nrepo: /r"))
        assertNull(parseGitCall("tool: terminal"))
    }
}
