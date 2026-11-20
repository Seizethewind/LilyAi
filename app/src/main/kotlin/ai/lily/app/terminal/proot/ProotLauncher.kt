package ai.lily.app.terminal.proot

import java.io.File

/** Where the pieces are. In the app, [proot] and [loader] are files in the native library folder (see docs/terminal.md). */
data class ProotPaths(
    val proot: File,
    val loader: File,
    val rootfs: File,
    /** A writable scratch folder proot uses for its own files. Must exist and be on the app's private storage. */
    val tmpDir: File,
)

/** A ready-to-start command: the host program, its arguments and the complete environment it gets. */
data class ProotCommand(val argv: List<String>, val env: Map<String, String>, val workDir: File)

/**
 * Builds the proot command line that starts a program inside the unpacked root file system.
 *
 * Why proot at all: since Android 10 an app may not run programs it unpacked into its own storage, but it may run the ones
 * shipped as native libraries. proot is shipped that way, and it then runs the guest's programs by translating system
 * calls, so the guest needs no permission of its own. See docs/terminal.md.
 */
object ProotLauncher {
    /** Host folders shown inside the guest. /dev, /proc and /sys are needed by almost every program. */
    val DEFAULT_BINDS = listOf("/dev", "/proc", "/sys")

    private const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * @param guestCommand program and arguments as the guest sees them, such as `/bin/sh -l`
     * @param guestCwd absolute folder inside the guest to start in
     * @param extraBinds host folder to guest folder pairs, such as the project folder
     * @param disableSeccomp turns off proot's seccomp speed-up, which a few devices handle badly; slower but safer
     */
    fun command(
        paths: ProotPaths,
        guestCommand: List<String> = listOf("/bin/sh", "-l"),
        guestCwd: String = "/root",
        extraBinds: List<Pair<File, String>> = emptyList(),
        term: String = "xterm-256color",
        disableSeccomp: Boolean = false,
    ): ProotCommand {
        require(guestCommand.isNotEmpty()) { "No program to run." }
        require(guestCommand.all { '\u0000' !in it }) { "A command part contains a null character." }
        requireGuestPath(guestCwd, "working folder")

        val argv = mutableListOf(
            paths.proot.absolutePath,
            "-r", paths.rootfs.absolutePath,
            "-0", // appear as root inside; there is no real root
            "--link2symlink", // hard links are not allowed on app storage; emulate them
            "--kill-on-exit",
            "-w", guestCwd,
        )
        for (bind in DEFAULT_BINDS) argv += listOf("-b", bind)
        for ((host, guest) in extraBinds) {
            requireGuestPath(guest, "bind target")
            argv += listOf("-b", "${host.absolutePath}:$guest")
        }
        argv += guestCommand

        val env = linkedMapOf(
            "PROOT_LOADER" to paths.loader.absolutePath,
            "PROOT_TMP_DIR" to paths.tmpDir.absolutePath,
            "HOME" to "/root",
            "USER" to "root",
            "TERM" to term,
            "LANG" to "C.UTF-8",
            "PATH" to GUEST_PATH,
        )
        if (disableSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        // The host process works from its own folder, never from inside the guest.
        return ProotCommand(argv, env, paths.tmpDir)
    }

    /** Problems that would stop a start, as readable text; empty when everything needed is there. */
    fun problems(paths: ProotPaths): List<String> = buildList {
        if (!paths.proot.isFile) add("proot is missing (${paths.proot.name}). Put it in jniLibs; see docs/terminal.md.")
        else if (!paths.proot.canExecute()) add("proot is not executable. The app must extract native libraries (useLegacyPackaging).")
        if (!paths.loader.isFile) add("The proot loader is missing (${paths.loader.name}).")
        if (!File(paths.rootfs, "bin/sh").let { java.nio.file.Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }) {
            add("The Linux environment is not installed.")
        }
    }

    private fun requireGuestPath(path: String, what: String) {
        require(path.startsWith("/")) { "The $what must be an absolute path inside the guest." }
        require('\u0000' !in path && '\n' !in path) { "The $what has a character that is not allowed." }
    }
}

/** Small files a fresh Alpine root needs before programs can use the network or their usual folders. */
object GuestSetup {
    /** Safe to call every start. Never overwrites a resolv.conf the person edited. */
    fun prepare(rootfs: File) {
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").mkdirs()
        File(rootfs, "etc").mkdirs()
        val resolv = File(rootfs, "etc/resolv.conf")
        // Android has no /etc/resolv.conf for the guest to use, so give it public resolvers.
        if (!resolv.exists()) resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists()) hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
    }
}
