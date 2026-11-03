package ai.lily.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PluginRegistryTest {
    private val id = "test.plugin"
    private val other = "external-app:com.other.app"

    private fun registry() = PluginRegistry(InMemoryPluginStore())

    private fun installed(r: InstallResult): InstallResult.Installed =
        r as? InstallResult.Installed ?: throw AssertionError("expected Installed, got $r")

    @Test
    fun aNewPluginIsOffAndHasNothingAllowed() {
        val r = registry()
        val result = installed(r.install(TestManifests.json()))
        assertFalse(result.updated)
        assertFalse(result.plugin.state.enabled)
        assertTrue(result.plugin.state.granted.isEmpty())
        assertTrue(result.plugin.state.autoApprove.isEmpty())
    }

    @Test
    fun aRejectedManifestStoresNothing() {
        val r = registry()
        val result = r.install(TestManifests.json(pkg = "com.other.app"))
        assertTrue(result is InstallResult.Rejected)
        assertTrue(r.list().isEmpty())
    }

    @Test
    fun onlyARequestedPermissionCanBeAllowed() {
        val r = registry()
        r.install(TestManifests.json())
        assertFalse(r.grant(id, other))
        assertTrue(r.grant(id, TestManifests.PERMISSION))
        assertEquals(setOf(TestManifests.PERMISSION), r.get(id)?.state?.granted)
        assertFalse(r.grant("nobody", TestManifests.PERMISSION))
    }

    @Test
    fun dontAskAgainNeedsTheToolAndItsPermission() {
        val r = registry()
        r.install(TestManifests.json())
        assertFalse(r.setAutoApprove(id, "run", true))
        r.grant(id, TestManifests.PERMISSION)
        assertFalse(r.setAutoApprove(id, "nope", true))
        assertTrue(r.setAutoApprove(id, "run", true))
        assertEquals(setOf("run"), r.get(id)?.state?.autoApprove)
        assertTrue(r.setAutoApprove(id, "run", false))
        assertTrue(r.get(id)?.state?.autoApprove?.isEmpty() == true)
    }

    @Test
    fun takingAPermissionBackAlsoClearsDontAskAgain() {
        val r = registry()
        r.install(TestManifests.json())
        r.grant(id, TestManifests.PERMISSION)
        r.setAutoApprove(id, "run", true)
        assertTrue(r.revoke(id, TestManifests.PERMISSION))
        val state = r.get(id)!!.state
        assertTrue(state.granted.isEmpty())
        assertTrue(state.autoApprove.isEmpty())
    }

    @Test
    fun anUpdateKeepsWhatIsStillAskedForAndNeverAddsAnything() {
        val r = registry()
        r.install(TestManifests.json())
        r.setEnabled(id, true)
        r.grant(id, TestManifests.PERMISSION)
        r.setAutoApprove(id, "run", true)

        val both = "\"${TestManifests.PERMISSION}\", \"$other\""
        val result = installed(r.install(TestManifests.json(version = "1.1.0", permissions = both)))
        assertTrue(result.updated)
        assertTrue(result.plugin.state.enabled)
        assertEquals(setOf(TestManifests.PERMISSION), result.plugin.state.granted)
        assertEquals(setOf("run"), result.plugin.state.autoApprove)
        assertTrue(result.droppedGrants.isEmpty())
        assertEquals("1.1.0", r.get(id)?.manifest?.version)
    }

    @Test
    fun anUpdateDropsAllowancesItNoLongerAsksFor() {
        val r = registry()
        r.install(TestManifests.json())
        r.grant(id, TestManifests.PERMISSION)
        r.setAutoApprove(id, "run", true)

        val result = installed(r.install(TestManifests.json(pkg = "com.other.app", permissions = "\"$other\"")))
        assertTrue(result.plugin.state.granted.isEmpty())
        assertEquals(setOf(TestManifests.PERMISSION), result.droppedGrants)
        assertEquals(setOf("run"), result.clearedAutoApprove)
    }

    @Test
    fun anUpdateThatChangesATool_clearsItsDontAskAgain() {
        val r = registry()
        r.install(TestManifests.json())
        r.grant(id, TestManifests.PERMISSION)
        r.setAutoApprove(id, "run", true)

        val result = installed(r.install(TestManifests.json(extras = """{"com.example.CMD":"{command} --more"}""")))
        assertEquals(setOf(TestManifests.PERMISSION), result.plugin.state.granted)
        assertTrue(result.plugin.state.autoApprove.isEmpty())
        assertEquals(setOf("run"), result.clearedAutoApprove)
    }

    @Test
    fun uninstallRemovesThePlugin() {
        val r = registry()
        r.install(TestManifests.json())
        assertTrue(r.uninstall(id))
        assertNull(r.get(id))
        assertFalse(r.uninstall(id))
    }

    @Test
    fun theDiskStoreKeepsEverythingAcrossRestarts() {
        val dir = Files.createTempDirectory("lily-plugins").toFile()
        try {
            val first = PluginRegistry(DiskPluginStore(dir))
            first.install(TestManifests.json())
            first.setEnabled(id, true)
            first.grant(id, TestManifests.PERMISSION)
            first.setAutoApprove(id, "run", true)

            val second = PluginRegistry(DiskPluginStore(dir))
            val plugin = second.get(id)
            assertNotNull(plugin)
            assertEquals(PluginState(true, setOf(TestManifests.PERMISSION), setOf("run")), plugin!!.state)
            assertEquals(1, second.list().size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theDiskStoreSkipsDamagedAndMismatchedFilesAndKeepsTheRest() {
        val dir = Files.createTempDirectory("lily-plugins").toFile()
        try {
            val store = DiskPluginStore(dir)
            PluginRegistry(store).install(TestManifests.json())
            File(dir, "broken.json").writeText("{not json")
            File(dir, "test.plugin.json").copyTo(File(dir, "renamed.json"))

            val list = store.list()
            assertEquals(listOf(id), list.map { it.manifest.id })
            assertEquals(2, store.problems.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aHandEditedFileCannotAllowMoreThanTheManifestAsks() {
        val dir = Files.createTempDirectory("lily-plugins").toFile()
        try {
            val store = DiskPluginStore(dir)
            PluginRegistry(store).install(TestManifests.json())
            val file = File(dir, "test.plugin.json")
            val edited = file.readText().replace("\"granted\":[]", "\"granted\":[\"$other\",\"${TestManifests.PERMISSION}\"]")
            file.writeText(edited)
            assertEquals(setOf(TestManifests.PERMISSION), store.get(id)?.state?.granted)
        } finally {
            dir.deleteRecursively()
        }
    }
}
