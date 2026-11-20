package ai.lily.app.terminal.spike

import ai.lily.app.terminal.env.EnvArch
import ai.lily.app.terminal.env.EnvException
import ai.lily.app.terminal.env.EnvLayout
import ai.lily.app.terminal.env.EnvManager
import ai.lily.app.terminal.env.EnvReleaseParser
import ai.lily.app.terminal.env.EnvState
import ai.lily.app.terminal.env.HttpEnvDownloader
import ai.lily.app.terminal.proot.GuestSetup
import ai.lily.app.terminal.proot.ProotCommand
import ai.lily.app.terminal.proot.ProotLauncher
import ai.lily.app.terminal.proot.ProotPaths
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Phase 0 spike (debug builds only): proves that this device can run a Linux shell through proot from the app.
 *
 * Start it with adb, giving the root file system archive, its SHA-256 and its size in bytes:
 *   adb shell am start -n ai.lily.app/.terminal.spike.ProotSpikeActivity \
 *     --es url https://... --es sha256 <64 hex> --el size <bytes>
 * Then press the buttons in order. See docs/terminal.md for what a pass looks like.
 */
class ProotSpikeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    private val layout by lazy { EnvLayout(File(filesDir, "terminal")) }
    private val paths by lazy {
        val tmp = File(cacheDir, "proot-tmp").apply { mkdirs() }
        val libs = File(applicationInfo.nativeLibraryDir)
        ProotPaths(File(libs, "libproot.so"), File(libs, "libproot-loader.so"), layout.rootfs, tmp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            column.addView(this)
        }
        button("1. Check files") { background { checkFiles() } }
        button("2. Install Linux environment") { background { install() } }
        button("3. Shell test") { background { shellTest(disableSeccomp = false) } }
        button("3b. Shell test, seccomp off") { background { shellTest(disableSeccomp = true) } }
        button("4. Install git, python, node") { background { toolsTest() } }
        button("Reset environment") { background { reset() } }

        log = TextView(this).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f }
        scroll = ScrollView(this).apply { addView(log) }
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        say("Device: ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun background(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: EnvException) {
                say("FAILED (${e.failure}): ${e.message}")
            } catch (e: Exception) {
                say("FAILED: ${e::class.java.simpleName}: ${e.message}")
            }
        }
    }

    private fun say(line: String) {
        runOnUiThread {
            log.append(line + "\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun checkFiles() {
        val problems = ProotLauncher.problems(paths)
        say("nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
        say("libproot.so: ${paths.proot.isFile}, executable ${paths.proot.canExecute()}")
        say("libproot-loader.so: ${paths.loader.isFile}")
        say(if (problems.isEmpty()) "Everything needed is here." else problems.joinToString("\n") { "- $it" })
    }

    private suspend fun install() {
        val url = intent.getStringExtra("url")
        val sha = intent.getStringExtra("sha256")
        val size = intent.getLongExtra("size", -1L)
        val arch = EnvArch.fromAbis(Build.SUPPORTED_ABIS.toList())
        if (url == null || sha == null || size <= 0 || arch == null) {
            say("Start this screen with --es url, --es sha256 and --el size (see the comment in ProotSpikeActivity). CPU supported: ${arch != null}")
            return
        }
        val manifest = """{"schema":1,"version":"spike","assets":{"${arch.distro}":{"url":"$url","sha256":"$sha","size":$size}}}"""
        val release = EnvReleaseParser.parse(manifest)
        val manager = EnvManager(layout, HttpEnvDownloader())
        val watcher = scope.launch {
            var lastBucket = -1L
            manager.state.collect { s ->
                if (s is EnvState.Installing) {
                    val bucket = if (s.total > 0) s.done * 10 / s.total else -1
                    if (bucket != lastBucket || s.total == 0L) {
                        lastBucket = bucket
                        say("${s.step} ${if (s.total > 0) "${s.done * 100 / s.total}%" else s.done}")
                    }
                }
            }
        }
        val started = System.currentTimeMillis()
        val installed = manager.ensure(release, arch)
        watcher.cancel()
        say(if (installed) "Installed in ${(System.currentTimeMillis() - started) / 1000}s." else "Already installed.")
        say("State: ${manager.state.value}")
    }

    private fun shellTest(disableSeccomp: Boolean) {
        val problems = ProotLauncher.problems(paths)
        if (problems.isNotEmpty()) {
            say(problems.joinToString("\n") { "- $it" })
            return
        }
        GuestSetup.prepare(layout.rootfs)
        val script = "uname -a; id; head -n 2 /etc/os-release; echo SPIKE_OK"
        val cmd = ProotLauncher.command(paths, listOf("/bin/sh", "-c", script), disableSeccomp = disableSeccomp)
        val started = System.currentTimeMillis()
        val (code, out) = exec(cmd, 60)
        val ok = code == 0 && "SPIKE_OK" in out
        say("${if (ok) "PASS" else "FAIL"}: exit $code in ${System.currentTimeMillis() - started} ms (seccomp ${if (disableSeccomp) "off" else "on"})")
    }

    private fun toolsTest() {
        if (ProotLauncher.problems(paths).isNotEmpty()) return say("Run steps 1 to 3 first.")
        GuestSetup.prepare(layout.rootfs)
        val script = "apk add --no-cache git python3 nodejs && git --version && python3 --version && node --version && echo TOOLS_OK"
        val cmd = ProotLauncher.command(paths, listOf("/bin/sh", "-c", script))
        val started = System.currentTimeMillis()
        val (code, out) = exec(cmd, 900)
        say("${if (code == 0 && "TOOLS_OK" in out) "PASS" else "FAIL"}: exit $code in ${(System.currentTimeMillis() - started) / 1000}s")
    }

    private suspend fun reset() {
        EnvManager(layout, HttpEnvDownloader()).reset()
        say("Environment removed.")
    }

    /** Runs [cmd] with exactly its own environment, shows output as it arrives, and returns the exit code and all output. */
    private fun exec(cmd: ProotCommand, timeoutSeconds: Long): Pair<Int, String> {
        val builder = ProcessBuilder(cmd.argv).directory(cmd.workDir).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment().putAll(cmd.env)
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            say("Could not start proot: ${e.message}")
            return -1 to ""
        }
        val captured = StringBuffer()
        val reader = thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                captured.append(line).append('\n')
                say(line)
            }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            say("Timed out after ${timeoutSeconds}s.")
            return -1 to captured.toString()
        }
        reader.join(2000)
        return process.exitValue() to captured.toString()
    }
}
