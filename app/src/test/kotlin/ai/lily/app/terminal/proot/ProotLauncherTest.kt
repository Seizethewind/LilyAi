package ai.lily.app.terminal.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotLauncherTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun paths() = ProotPaths(
        proot = File(tmp.root, "lib/libproot.so"),
        loader = File(tmp.root, "lib/libproot-loader.so"),
        rootfs = File(tmp.root, "rootfs"),
        tmpDir = File(tmp.root, "tmp"),
    )

    @Test fun buildsTheCommandLineInOrder() {
        val p = paths()
        val c = ProotLauncher.command(p, listOf("/bin/sh", "-c", "echo hi"), "/work", listOf(File("/sdcard/proj") to "/workspace"))
        val a = c.argv
        assertEquals(p.proot.absolutePath, a.first())
        assertEquals(listOf("-r", p.rootfs.absolutePath), a.subList(1, 3))
        assertTrue("-0" in a && "--link2symlink" in a && "--kill-on-exit" in a)
        assertEquals("/work", a[a.indexOf("-w") + 1])
        assertTrue(a.containsAll(listOf("/dev", "/proc", "/sys", "/sdcard/proj:/workspace")))
        // The guest program is the very end, after every proot option.
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), a.takeLast(3))
        assertEquals(p.tmpDir, c.workDir)
    }

    @Test fun givesTheGuestAMinimalCleanEnvironment() {
        val p = paths()
        val env = ProotLauncher.command(p).env
        assertEquals(p.loader.absolutePath, env["PROOT_LOADER"])
        assertEquals(p.tmpDir.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("/root", env["HOME"])
        assertEquals("xterm-256color", env["TERM"])
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
        assertFalse(env.containsKey("PROOT_NO_SECCOMP"))
        assertEquals("1", ProotLauncher.command(p, disableSeccomp = true).env["PROOT_NO_SECCOMP"])
    }

    @Test fun refusesBadGuestPathsAndEmptyCommands() {
        val p = paths()
        for (bad in listOf<() -> Unit>(
            { ProotLauncher.command(p, guestCwd = "relative") },
            { ProotLauncher.command(p, guestCwd = "/a\nb") },
            { ProotLauncher.command(p, guestCommand = emptyList()) },
            { ProotLauncher.command(p, extraBinds = listOf(File("/x") to "nope")) },
        )) {
            try {
                bad()
                fail("expected a refusal")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test fun listsWhatIsMissing() {
        val p = paths()
        val all = ProotLauncher.problems(p)
        assertEquals(3, all.size)
        p.proot.parentFile.mkdirs()
        p.proot.writeText("x"); p.proot.setExecutable(true)
        p.loader.writeText("x")
        File(p.rootfs, "bin").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(p.rootfs, "bin/sh").toPath(), java.nio.file.Paths.get("/bin/busybox"))
        assertEquals(emptyList<String>(), ProotLauncher.problems(p))
    }

    @Test fun guestSetupKeepsAnEditedResolvConf() {
        val root = tmp.newFolder("rootfs2")
        GuestSetup.prepare(root)
        assertTrue(File(root, "etc/resolv.conf").readText().contains("1.1.1.1"))
        File(root, "etc/resolv.conf").writeText("nameserver 9.9.9.9\n")
        GuestSetup.prepare(root)
        assertEquals("nameserver 9.9.9.9\n", File(root, "etc/resolv.conf").readText())
        assertTrue(File(root, "root").isDirectory && File(root, "tmp").isDirectory)
    }
}
