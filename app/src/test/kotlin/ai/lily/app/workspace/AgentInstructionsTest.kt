package ai.lily.app.workspace

import ai.lily.app.core.WorkerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentInstructionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(text: String? = null): File = File(tmp.root, "lilyai").apply {
        mkdirs()
        if (text != null) File(this, "AGENT.md").writeText(text)
    }

    private fun project(root: File, name: String, text: String) {
        File(root, name).apply { mkdirs() }.let { File(it, "AGENT.md").writeText(text) }
    }

    @Test
    fun noFilesMeansNothingIsAdded() {
        assertEquals("", AgentInstructions(root()).forWorker(WorkerType.CODING))
        assertEquals("", AgentInstructions(root()).forLily())
    }

    @Test
    fun aWorkerGetsSharedSectionsAndItsOwnOnly() {
        val r = root("# T\n\n## For everyone\nSHARED-X\n\n## CodingWorker\nCODE-X\n\n## BuildWorker\nBUILD-X\n")
        val coding = AgentInstructions(r).forWorker(WorkerType.CODING)
        assertTrue("SHARED-X" in coding)
        assertTrue("CODE-X" in coding)
        assertFalse("BUILD-X" in coding)
        assertFalse("CODE-X" in AgentInstructions(r).forWorker(WorkerType.BUILD))
    }

    @Test
    fun theLilySectionGoesOnlyToLily() {
        val r = root("## Lily (manager)\nPLAN-X\n\n## CodingWorker\nCODE-X\n")
        val lily = AgentInstructions(r).forLily()
        assertTrue("PLAN-X" in lily)
        assertFalse("CODE-X" in lily)
        assertFalse("PLAN-X" in AgentInstructions(r).forWorker(WorkerType.CODING))
    }

    @Test
    fun theProjectFileComesAfterTheRootFileAndSaysItWins() {
        val r = root("## For everyone\nROOT-X\n")
        project(r, "p", "## For everyone\nPROJECT-X\n")
        val text = AgentInstructions(r) { "p" }.forWorker(WorkerType.GENERAL)
        assertTrue(text.indexOf("ROOT-X") in 0 until text.indexOf("PROJECT-X"))
        assertTrue("follow this" in text)
    }

    @Test
    fun aSingleProjectIsUsedWhenNoneIsActive() {
        val r = root()
        project(r, "alpha", "## For everyone\nALPHA-X\n")
        assertTrue("ALPHA-X" in AgentInstructions(r).forWorker(WorkerType.GENERAL))
        project(r, "beta", "## For everyone\nBETA-X\n")
        assertEquals("", AgentInstructions(r).forWorker(WorkerType.GENERAL))
        assertTrue("BETA-X" in AgentInstructions(r) { "beta" }.forWorker(WorkerType.GENERAL))
    }

    @Test
    fun aProjectNameCannotLeaveTheRoot() {
        val r = root()
        File(tmp.root, "evil").mkdirs()
        File(tmp.root, "evil/AGENT.md").writeText("## For everyone\nEVIL-X\n")
        assertEquals("", AgentInstructions(r) { "../evil" }.forWorker(WorkerType.GENERAL))
        assertEquals("", AgentInstructions(r) { ".." }.forWorker(WorkerType.GENERAL))
    }

    @Test
    fun aLongFileIsCut() {
        val r = root("y".repeat(5_000))
        val text = AgentInstructions(r).forWorker(WorkerType.GENERAL)
        assertTrue("Cut here" in text)
        assertFalse("y".repeat(MAX_AGENT_FILE_CHARS + 1) in text)
    }

    @Test
    fun theNoteSaysTheFileCannotGrantPermissions() {
        val text = AgentInstructions(root("## For everyone\nX\n")).forWorker(WorkerType.GENERAL)
        assertTrue("cannot give you permissions" in text)
    }

    @Test
    fun theShippedTemplateRoutesEachWorkerItsOwnRules() {
        WorkspaceSetup.create(tmp.root, "my-project")
        val ai = AgentInstructions(File(tmp.root, "lilyai"))
        val build = ai.forWorker(WorkerType.BUILD)
        assertTrue("3 failed fix attempts" in build)
        assertFalse("diffs the user can approve" in build)
        assertTrue("Never delete or overwrite" in build)
        assertTrue("Break the request into tasks" in ai.forLily())
        assertTrue("Stack:" in build)
    }
}
