package ai.lily.app.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteMirrorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fs = FakeRemoteFs()
    private lateinit var root: File
    private lateinit var manifest: File

    private fun mirror(limits: MirrorLimits = MirrorLimits()): RemoteMirror {
        if (!::root.isInitialized) {
            root = tmp.newFolder("ws")
            manifest = File(tmp.root, "ws.manifest.json")
        }
        return RemoteMirror(fs, root, manifest, limits)
    }

    private fun local(path: String) = File(root, path)

    private fun edit(path: String, text: String) {
        local(path).writeText(text)
    }

    // ---- pull ----

    @Test
    fun aFirstPullDownloadsFilesAndLeavesDependencyFoldersOut() = runBlocking<Unit> {
        fs.putFile("README", "hello")
        fs.putFile("src/app.js", "run()")
        fs.putFile("node_modules/lib/index.js", "x")
        fs.putFile(".git/config", "y")

        val report = mirror().pull()

        assertEquals(listOf("README", "src/app.js"), report.downloaded.sorted())
        assertEquals("run()", local("src/app.js").readText())
        assertFalse(local("node_modules").exists())
        assertFalse(local(".git").exists())
        assertTrue(report.complete)
    }

    @Test
    fun aSecondPullDownloadsNothingWhenNothingChanged() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        val readsBefore = fs.reads

        val report = m.pull()

        assertTrue(report.downloaded.isEmpty())
        assertEquals(1, report.unchanged)
        assertEquals(readsBefore, fs.reads)
    }

    @Test
    fun aChangeOnTheServerComesDown() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        fs.putFile("a.txt", "two")

        val report = m.pull()

        assertEquals(listOf("a.txt"), report.downloaded)
        assertEquals("two", local("a.txt").readText())
    }

    @Test
    fun aLocalEditSurvivesAPullEvenWhenTheServerChangedToo() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        edit("a.txt", "my version")
        fs.putFile("a.txt", "their version")

        val report = m.pull()

        assertEquals(listOf("a.txt"), report.localKept)
        assertTrue(report.downloaded.isEmpty())
        assertEquals("my version", local("a.txt").readText())
    }

    @Test
    fun aLocalEditIsKeptWhenTheServerDidNotChange() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        edit("a.txt", "my version")

        val report = m.pull()

        assertTrue(report.localKept.isEmpty())
        assertEquals("my version", local("a.txt").readText())
    }

    @Test
    fun aFileDeletedOnTheServerIsRemovedLocallyOnlyIfUntouched() = runBlocking<Unit> {
        fs.putFile("gone.txt", "same")
        fs.putFile("edited.txt", "same")
        val m = mirror()
        m.pull()
        edit("edited.txt", "changed here")
        fs.delete("gone.txt")
        fs.delete("edited.txt")

        val report = m.pull()

        assertEquals(listOf("gone.txt"), report.removed)
        assertFalse(local("gone.txt").exists())
        assertEquals(listOf("edited.txt"), report.localKept)
        assertEquals("changed here", local("edited.txt").readText())
    }

    @Test
    fun nothingIsDeletedWhenTheListingWasIncomplete() = runBlocking<Unit> {
        fs.putFile("src/a.txt", "one")
        fs.putFile("b.txt", "two")
        val m = mirror()
        m.pull()
        fs.brokenFolders += "src"

        val report = m.pull()

        assertFalse(report.complete)
        assertTrue(report.removed.isEmpty())
        assertEquals("one", local("src/a.txt").readText())
        assertTrue(report.skipped.getValue("src").startsWith("could not list"))
    }

    @Test
    fun aFileDeletedLocallyIsFetchedAgain() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        local("a.txt").delete()

        val report = m.pull()

        assertEquals(listOf("a.txt"), report.downloaded)
        assertEquals("one", local("a.txt").readText())
    }

    @Test
    fun namesFromAHostileServerNeverTouchTheDisk() = runBlocking<Unit> {
        fs.putFile("ok.txt", "fine")
        fs.extraEntries[""] = mutableListOf(
            RemoteEntry("..", false, 3, 1),
            RemoteEntry("a\\b.txt", false, 3, 1),
            RemoteEntry("x\u0000y", false, 3, 1),
            RemoteEntry("", false, 3, 1),
        )
        fs.putFile("../evil.txt", "pwned")

        val report = mirror().pull()

        assertEquals(listOf("ok.txt"), report.downloaded)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertEquals(4, report.skipped.values.count { it == "unsafe name" })
    }

    @Test
    fun linksAreNotFollowed() = runBlocking<Unit> {
        fs.putFile("real.txt", "x")
        fs.extraEntries[""] = mutableListOf(RemoteEntry("shortcut", true, 0, 1, isLink = true))

        val report = mirror().pull()

        assertEquals("link", report.skipped["shortcut"])
    }

    @Test
    fun bigFilesAndTheFileLimitAreRespected() = runBlocking<Unit> {
        fs.putFile("big.bin", "x".repeat(50))
        fs.putFile("a.txt", "1")
        fs.putFile("b.txt", "2")
        fs.putFile("c.txt", "3")

        val report = mirror(MirrorLimits(maxFiles = 2, maxFileBytes = 10)).pull()

        assertFalse(report.complete)
        assertEquals(2, report.downloaded.size + report.skipped.count { it.value.startsWith("larger") })
        assertTrue(report.skipped.values.any { it == "file limit reached" })
    }

    @Test
    fun theTotalDownloadBudgetStopsDownloadsAndDeletions() = runBlocking<Unit> {
        fs.putFile("a.txt", "12345")
        fs.putFile("b.txt", "12345")

        val report = mirror(MirrorLimits(maxTotalBytes = 6)).pull()

        assertEquals(1, report.downloaded.size)
        assertFalse(report.complete)
        assertTrue(report.skipped.values.contains("download budget used up"))
    }

    @Test
    fun aDamagedManifestIsTreatedAsEmptyAndNothingIsClobbered() = runBlocking<Unit> {
        fs.putFile("same.txt", "one")
        fs.putFile("differs.txt", "server")
        val m = mirror()
        edit("same.txt", "one")
        edit("differs.txt", "mine")
        manifest.writeText("{ this is not json")

        val report = m.pull()

        assertEquals(listOf("differs.txt"), report.localKept)
        assertEquals("mine", local("differs.txt").readText())
        assertTrue(report.downloaded.isEmpty())
        assertEquals(1, report.unchanged)
    }

    // ---- push ----

    @Test
    fun aSavedFileIsUploadedAndTheNextPushNeedsNoPull() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        edit("a.txt", "two")

        assertEquals(PushResult.Uploaded("a.txt"), m.push("a.txt"))
        edit("a.txt", "three")
        assertEquals(PushResult.Uploaded("a.txt"), m.push("a.txt"))

        assertEquals("three", fs.textOf("a.txt"))
    }

    @Test
    fun aNewFileInANewFolderCreatesTheFoldersOnTheServer() = runBlocking<Unit> {
        val m = mirror()
        m.pull()
        edit("x.txt", "keep root")
        local("deep/er").mkdirs()
        edit("deep/er/new.txt", "hello")

        assertEquals(PushResult.Uploaded("deep/er/new.txt"), m.push("deep/er/new.txt"))

        assertEquals("hello", fs.textOf("deep/er/new.txt"))
        assertTrue(fs.nodes.getValue("deep").bytes == null)
        assertTrue(fs.nodes.getValue("deep/er").bytes == null)
    }

    @Test
    fun aServerChangeBlocksThePushUnlessForced() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        edit("a.txt", "mine")
        fs.putFile("a.txt", "theirs")

        assertEquals(PushResult.Conflict("a.txt", ConflictKind.REMOTE_CHANGED), m.push("a.txt"))
        assertEquals("theirs", fs.textOf("a.txt"))

        assertEquals(PushResult.Uploaded("a.txt"), m.push("a.txt", force = true))
        assertEquals("mine", fs.textOf("a.txt"))
    }

    @Test
    fun aFileDeletedOnTheServerIsAConflict() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        edit("a.txt", "mine")
        fs.delete("a.txt")

        assertEquals(PushResult.Conflict("a.txt", ConflictKind.REMOTE_DELETED), m.push("a.txt"))
    }

    @Test
    fun aNewLocalFileThatAlreadyExistsOnTheServerIsAConflict() = runBlocking<Unit> {
        fs.putFile("a.txt", "theirs")
        val m = mirror()
        m.pull()
        // The pull recorded it; simulate a file made locally under a name the server got meanwhile.
        edit("fresh.txt", "mine")
        fs.putFile("fresh.txt", "theirs too")

        assertEquals(PushResult.Conflict("fresh.txt", ConflictKind.REMOTE_APPEARED), m.push("fresh.txt"))
        assertEquals("theirs too", fs.textOf("fresh.txt"))
    }

    @Test
    fun pushingAMissingFileOrAnUnsafePathFails() = runBlocking<Unit> {
        val m = mirror()

        assertTrue(m.push("nope.txt") is PushResult.Failed)
        assertTrue(m.push("../x.txt") is PushResult.Failed)
    }

    @Test
    fun aServerErrorBecomesAFailureNotACrash() = runBlocking<Unit> {
        fs.putFile("blocker", "i am a file")
        val m = mirror()
        m.pull()
        local("blocker").delete()
        local("blocker").mkdirs()
        edit("blocker/child.txt", "x")

        val result = m.push("blocker/child.txt", force = true)

        assertTrue(result is PushResult.Failed)
        assertEquals("i am a file", fs.textOf("blocker"))
    }

    // ---- take remote ----

    @Test
    fun choosingTheServersVersionReplacesTheLocalOne() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        edit("a.txt", "mine")
        fs.putFile("a.txt", "theirs")

        assertNull(m.takeRemote("a.txt"))

        assertEquals("theirs", local("a.txt").readText())
        assertEquals(PushResult.Uploaded("a.txt"), m.push("a.txt"))
    }

    @Test
    fun choosingTheServersVersionOfADeletedFileDeletesTheLocalCopy() = runBlocking<Unit> {
        fs.putFile("a.txt", "one")
        val m = mirror()
        m.pull()
        fs.delete("a.txt")

        assertNull(m.takeRemote("a.txt"))

        assertFalse(local("a.txt").exists())
    }

    // ---- manifest ----

    @Test
    fun theManifestRoundTripsAndDropsUnsafePaths() {
        val files = mapOf("a/b.txt" to Baseline(3, 99, "abc"), "../x" to Baseline(1, 1, "def"))

        val back = ManifestCodec.decode(ManifestCodec.encode(files))

        assertEquals(mapOf("a/b.txt" to Baseline(3, 99, "abc")), back)
    }
}
