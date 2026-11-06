package ai.lily.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceSetupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun projectNameIsMadeSafe() {
        assertEquals("my-project", WorkspaceSetup.projectName("My Project!"))
        assertEquals("a_b-c", WorkspaceSetup.projectName("  a_b c "))
        assertEquals(WorkspaceSetup.DEFAULT_PROJECT, WorkspaceSetup.projectName("   "))
        assertEquals(WorkspaceSetup.DEFAULT_PROJECT, WorkspaceSetup.projectName("../.."))
    }

    @Test
    fun baseFolderNeverDoublesLilyai() {
        assertEquals(File("/sdcard/Documents"), WorkspaceSetup.baseFolder("/sdcard/Documents/lilyai"))
        assertEquals(File("/sdcard/Documents"), WorkspaceSetup.baseFolder("  /sdcard/Documents/lilyai/ "))
        assertEquals(File("/sdcard/Documents"), WorkspaceSetup.baseFolder("/sdcard/Documents/"))
        assertEquals(File("/sdcard/Documents"), WorkspaceSetup.baseFolder("/sdcard/Documents/LilyAI"))
        assertEquals(File("/sdcard/Documents/lilyai-notes"), WorkspaceSetup.baseFolder("/sdcard/Documents/lilyai-notes"))
    }

    @Test
    fun createsRootAgentAndProjectFiles() {
        val result = WorkspaceSetup.create(tmp.root, "Calculator")
        val root = File(tmp.root, "lilyai")
        assertEquals(root, result.root)
        assertTrue(File(root, "AGENT.md").isFile)
        assertTrue(File(root, "calculator/README.md").isFile)
        assertTrue(File(root, "calculator/AGENT.md").isFile)
        assertTrue(File(root, "calculator/README.md").readText().startsWith("# calculator"))
        assertEquals(3, result.created.size)
        assertTrue(result.kept.isEmpty())
    }

    @Test
    fun typingTheLilyaiFolderItselfStillGivesOneLilyaiFolder() {
        val typed = File(tmp.root, "lilyai").path
        WorkspaceSetup.create(WorkspaceSetup.baseFolder(typed), "app")
        assertTrue(File(tmp.root, "lilyai/AGENT.md").isFile)
        assertTrue(!File(tmp.root, "lilyai/lilyai").exists())
    }

    @Test
    fun neverOverwritesWhatIsThere() {
        val project = File(tmp.root, "lilyai/app").apply { mkdirs() }
        File(project, "README.md").writeText("mine")
        val result = WorkspaceSetup.create(tmp.root, "app")
        assertEquals("mine", File(project, "README.md").readText())
        assertTrue("app/README.md" in result.kept)
        assertEquals(2, result.created.size)
    }

    @Test
    fun anExistingAgentsFileCountsAsTheRootInstructions() {
        val root = File(tmp.root, "lilyai").apply { mkdirs() }
        File(root, "AGENTS.md").writeText("mine")
        WorkspaceSetup.create(tmp.root, "app")
        assertTrue(!File(root, "AGENT.md").exists())
    }

    @Test
    fun runningTwiceChangesNothing() {
        WorkspaceSetup.create(tmp.root, "app")
        val again = WorkspaceSetup.create(tmp.root, "app")
        assertTrue(again.created.isEmpty())
        assertEquals(3, again.kept.size)
    }

    @Test(expected = IllegalStateException::class)
    fun aFolderThatCannotBeMadeIsAnErrorNotASilentSkip() {
        // A plain file where the parent folder should be: nothing can be made inside it.
        val blocker = tmp.newFile("blocker")
        WorkspaceSetup.create(blocker, "app")
    }
}
