package ai.lily.app.terminal.ui

import ai.lily.app.terminal.env.AlpineReleases
import ai.lily.app.terminal.env.EnvArch
import ai.lily.app.terminal.env.EnvException
import ai.lily.app.terminal.env.EnvLayout
import ai.lily.app.terminal.env.EnvManager
import ai.lily.app.terminal.env.EnvState
import ai.lily.app.terminal.env.HttpEnvDownloader
import ai.lily.app.terminal.TerminalService
import ai.lily.app.terminal.proot.GuestSetup
import ai.lily.app.terminal.proot.ProotLauncher
import ai.lily.app.terminal.proot.ProotPaths
import ai.lily.app.terminal.proot.toSessionSpec
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** One terminal tab. [version] changes whenever the session's state does, so the screen redraws its title. */
class TerminalTab(val id: Int, val session: TerminalSession) {
    var title by mutableStateOf("Shell $id")
    var finished by mutableStateOf(false)
}

/**
 * Everything the Terminal section needs: the Linux environment (download, state), the open sessions, and the sticky
 * Ctrl and Alt buttons. One per process ([get]), so closing the screen or the activity does not end a shell; while a shell
 * runs, [ai.lily.app.terminal.TerminalService] keeps the process alive. Sessions still end if Android stops the app.
 */
class TerminalHost private constructor(private val context: Context) {
    /** Lives as long as the process, so a download or a shell carries on when the screen is closed. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val layout = EnvLayout(File(context.filesDir, "terminal"))
    val env = EnvManager(layout, HttpEnvDownloader())
    val arch: EnvArch? = EnvArch.fromAbis(Build.SUPPORTED_ABIS.toList())

    private val paths: ProotPaths by lazy {
        val libs = File(context.applicationInfo.nativeLibraryDir)
        ProotPaths(File(libs, "libproot.so"), File(libs, "libproot-loader.so"), layout.rootfs, File(context.cacheDir, "proot-tmp"))
    }

    val tabs = mutableStateListOf<TerminalTab>()
    var selected by mutableIntStateOf(0)
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    /** The last install error, or a start error, shown on the screen. Cleared when something starts. */
    var error by mutableStateOf<String?>(null)
    var seccompOff by mutableStateOf(false)

    /** The view currently showing a session; set by the screen. Callbacks from the sessions poke it to redraw. */
    var view: TerminalView? = null

    private var installJob: Job? = null
    private var nextId = 1

    val installing: Boolean get() = installJob?.isActive == true

    fun refresh() = env.refresh()

    /** What stops a shell from starting, other than the environment not being installed yet. */
    fun problems(): List<String> = ProotLauncher.problems(paths).filter { !it.startsWith("The Linux environment") }

    fun install() {
        if (installing) return
        val arch = arch ?: run {
            error = "This phone's CPU (${Build.SUPPORTED_ABIS.joinToString()}) has no Linux environment build."
            return
        }
        error = null
        installJob = scope.launch {
            try {
                val yamlUrl = AlpineReleases.yamlUrl(arch)
                val yaml = HttpEnvDownloader().fetchText(yamlUrl, maxBytes = 512 * 1024)
                env.install(AlpineReleases.release(yaml, arch), arch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnvException) {
                error = e.message
            } catch (e: Exception) {
                error = "${e::class.java.simpleName}: ${e.message}"
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    fun reset() {
        scope.launch {
            closeAll()
            try {
                env.reset()
                error = null
            } catch (e: EnvException) {
                error = e.message
            }
        }
    }

    fun newTab() {
        val blockers = problems()
        if (blockers.isNotEmpty()) {
            error = blockers.joinToString("\n")
            return
        }
        try {
            GuestSetup.prepare(layout.rootfs)
            paths.tmpDir.mkdirs()
            val spec = ProotLauncher.command(paths, disableSeccomp = seccompOff).toSessionSpec()
            val id = nextId++
            lateinit var tab: TerminalTab
            val client = SessionClient(
                onChange = { view?.onScreenUpdated() },
                onTitle = { title -> if (title.isNotBlank()) tab.title = title },
                onFinished = { tab.finished = true; view?.onScreenUpdated(); refreshService() },
            )
            val session = TerminalSession(spec.program, spec.cwd, spec.args, spec.env, TRANSCRIPT_ROWS, client)
            tab = TerminalTab(id, session)
            tabs.add(tab)
            selected = tabs.lastIndex
            error = null
            startService()
        } catch (e: Exception) {
            error = "Could not start a shell: ${e::class.java.simpleName}: ${e.message}"
        }
    }

    fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        tab.session.finishIfRunning()
        tabs.removeAt(index)
        selected = selected.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        refreshService()
    }

    fun closeAll() {
        tabs.forEach { it.session.finishIfRunning() }
        tabs.clear()
        selected = 0
        TerminalService.stop(context)
    }

    private fun running() = tabs.count { !it.finished }

    /** Starts the service that keeps the process alive. Only from something the person did on screen. */
    private fun startService() {
        try {
            TerminalService.start(context, running())
        } catch (e: Exception) {
            // The shell still works while the app is open; it just may not survive in the background.
            error = "Could not keep the shell running in the background: ${e::class.java.simpleName}: ${e.message}"
        }
    }

    /** After a shell ends: update the count, or stop the service when none is left. Never starts it (not allowed from the background). */
    private fun refreshService() {
        val n = running()
        if (n > 0) TerminalService.update(context, n) else TerminalService.stop(context)
    }

    /** Sends an extra-keys press. Letters and symbols go as text; named keys go through the view so modes are respected. */
    fun sendKey(keyCode: Int) {
        view?.handleKeyCode(keyCode, 0)
    }

    fun sendText(text: String) {
        val v = view ?: return
        text.codePoints().forEach { v.inputCodePoint(it, false, false) }
    }

    fun paste() {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotEmpty()) tabs.getOrNull(selected)?.session?.emulator?.paste(text)
    }

    /** The client the view needs. One instance is shared by every tab. */
    val viewClient: TerminalViewClient = object : TerminalViewClient {
        override fun onScale(scale: Float) = scale
        override fun onSingleTapUp(e: MotionEvent) {
            val v = view ?: return
            v.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
        override fun onLongPress(event: MotionEvent) = false
        override fun readControlKey() = ctrl
        override fun readAltKey() = alt
        override fun readShiftKey() = false
        override fun readFnKey() = false

        /** The sticky Ctrl and Alt buttons apply to one key, then let go. */
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
            if (ctrl) ctrl = false
            if (alt) alt = false
            return false
        }
        override fun onEmulatorSet() {}
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    private inner class SessionClient(
        val onChange: () -> Unit,
        val onTitle: (String) -> Unit,
        val onFinished: () -> Unit,
    ) : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = onChange()
        override fun onTitleChanged(changedSession: TerminalSession) = onTitle(changedSession.title.orEmpty())
        override fun onSessionFinished(finishedSession: TerminalSession) = onFinished()
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) = paste()
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) = onChange()
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    }

    companion object {
        private const val TRANSCRIPT_ROWS = 2000

        @Volatile private var instance: TerminalHost? = null

        /** The one host for this process. */
        fun get(context: Context): TerminalHost =
            instance ?: synchronized(this) { instance ?: TerminalHost(context.applicationContext).also { instance = it } }
    }
}

/** Whether the environment can start a shell right now. */
val EnvState.isReady: Boolean get() = this is EnvState.Ready
