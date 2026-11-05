package ai.lily.app.editor

import ai.lily.app.LilyFactory
import ai.lily.app.SettingsScreen
import ai.lily.app.core.Lily
import ai.lily.app.core.TaskResult
import ai.lily.app.lib.editor.DraftStore
import ai.lily.app.plugins.PluginServices
import ai.lily.app.plugins.fillPrompt
import ai.lily.app.remote.RemoteHub
import ai.lily.app.remote.RemoteStore
import ai.lily.app.settings.EncryptedSettingsStore
import ai.lily.app.shell.AiHost
import ai.lily.app.shell.AiNav
import ai.lily.app.shell.AiState
import ai.lily.app.shell.AppBottomBar
import ai.lily.app.shell.AppSidebar
import ai.lily.app.shell.BrowseScreen
import ai.lily.app.shell.BrowseState
import ai.lily.app.shell.ChatScreen
import ai.lily.app.shell.ChatState
import ai.lily.app.shell.Section
import ai.lily.app.shell.ShellNav
import ai.lily.app.shell.TaskQueue
import ai.lily.app.shell.TaskService
import ai.lily.app.shell.TaskStore
import ai.lily.app.ui.LilyTheme
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The app. Six sections: Work (the Acode style code editor with Lily's coding and file workers), Chat (free talk with Lily, and the
 * queue of background tasks), Browse, Plugins, AI (Lily, workers, routers and models, behind a drawer) and Settings. On a phone they sit in a bar along the
 * bottom, within thumb's reach; on a wide screen they run down the left edge. Files live in the app's own `workspace` folder, or,
 * while a remote connection is open, in the local copy of that server folder.
 */
class EditorActivity : ComponentActivity() {
    private lateinit var state: EditorState
    private var browse: BrowseState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val remote = RemoteHub.active
        val root = remote?.localRoot ?: File(filesDir, "workspace").apply { mkdirs() }
        val drafts = DraftStore(File(remote?.dir ?: filesDir, "drafts"))
        val prefs = EditorPrefs(applicationContext, remote?.profile?.id.orEmpty())
        state = EditorState(root, drafts, prefs, fonts = ai.lily.app.lib.editor.FontStore(File(filesDir, "fonts")))

        // The phone's file picker, for adding a font file. Fonts have no single file type, so every file is offered and the app checks it.
        val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) lifecycleScope.launch { addFont(uri) }
        }
        state.importFont = { fontPicker.launch(arrayOf("*/*")) }

        // Remote workspaces. Connecting or disconnecting rebuilds this screen on the right folder.
        val remoteUi = RemoteUi(
            store = RemoteStore(applicationContext),
            scope = lifecycleScope,
            say = { state.message = it },
            onSessionChanged = { recreate() },
        )
        if (remote != null) remoteUi.watchSaves(state)

        val ops = WorkspaceOps(state, drafts) { change -> remoteUi.onChange(change) }
        val ui = EditorUi()
        val session = AgentSession(state)

        // Plugins: installed ones are kept in the app's `plugins` folder. The bundled ones (Termux, Git control) arrive off, with nothing allowed.
        val plugins = PluginServices(applicationContext).also { it.installBundled() }
        // Git control for the person: the same Termux git tools Lily's workers get, behind a dialog in the command palette.
        val git = GitState(applicationContext, plugins, lifecycleScope)

        // Which section shows, and the browser page that stays alive while another section is open.
        val nav = ShellNav()
        val aiNav = AiNav()
        val browser = BrowseState(this).also { browse = it }
        val settingsStore = EncryptedSettingsStore(applicationContext)

        // Termux's run-command permission is a runtime one: declaring it is not enough, Android must be asked.
        val termuxPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        fun askTermuxPermission() {
            val on = listOf("lily.termux", GitState.ID).any { plugins.registry.get(it)?.state?.enabled == true }
            if (on && checkSelfPermission(TERMUX_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
                termuxPermission.launch(TERMUX_RUN_COMMAND)
            }
        }

        // Lily is built the first time she is asked, and again when the person changes a plugin, a worker or the settings, so a
        // plugin turned on a moment ago gives her its tools. The project folder lets her coding worker find relevant files beyond the open tabs.
        var lily: Lily? = null
        var builtFor = -1
        // The AI section's state lives here, not in its screen, so the models it loaded are still there when the person comes back to it.
        val ai = AiState(settingsStore, onChanged = { lily = null })
        fun currentLily(): Lily {
            val wanted = plugins.version
            return lily.takeIf { builtFor == wanted } ?: LilyFactory.create(
                settingsStore.load(), session.bridge, root, plugins.host, gitRepo = { git.repo.trim() },
            ).also {
                lily = it
                builtFor = wanted
            }
        }
        session.responder = { request ->
            withContext(Dispatchers.Default) { currentLily().handle(request) }
                .joinToString("\n\n") { it.output.trim() }
        }

        // Chat and the task queue ask Lily the same way. A queued task throws when she failed, so the queue stops and asks the person.
        suspend fun askLily(text: String): List<TaskResult> = withContext(Dispatchers.Default) { currentLily().handle(text) }
        val chat = ChatState(lifecycleScope) { text -> askLily(text).joinToString("\n\n") { it.output.trim() } }
        val queue = TaskQueue(
            lifecycleScope,
            run = { text ->
                val results = askLily(text)
                results.firstOrNull { !it.success }?.let { failed -> error(failed.output) }
                results.joinToString("\n\n") { it.output.trim() }
            },
            needsReview = { session.proposals.isNotEmpty() },
        )

        // Background running. While the queue has work, a foreground service keeps the process alive and its notification shows how
        // many tasks need the person. Unfinished tasks are saved, so a killed app queues them again at the next start.
        val store = TaskStore(File(filesDir, "tasks.json"))
        store.load().forEach { queue.add(it) }
        val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
        lifecycleScope.launch {
            snapshotFlow { queue.busy }.collect { busy ->
                if (busy) {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    TaskService.start(this@EditorActivity)
                } else {
                    TaskService.stop(this@EditorActivity)
                }
            }
        }
        lifecycleScope.launch {
            snapshotFlow { queue.needYou }.collect { need -> if (queue.busy) TaskService.update(this@EditorActivity, need) }
        }
        lifecycleScope.launch {
            snapshotFlow { queue.unfinished }.collect { texts -> withContext(Dispatchers.IO) { store.save(texts) } }
        }

        // Extra entries in the command palette.
        val extraCommands = listOf(
            ExtraCommand("Ask Lily") { ui.ask = true; askTermuxPermission() },
            ExtraCommand("Git control") { git.open = true; askTermuxPermission() },
            ExtraCommand("Remote workspaces") { remoteUi.manager = true },
            ExtraCommand("Plugins") { nav.section = Section.Plugins; askTermuxPermission() },
            ExtraCommand("New folder") { ui.dialog = WsDialog.NewFolder },
            ExtraCommand("Rename or move file or folder") { ui.dialog = WsDialog.PickRename },
            ExtraCommand("Delete file or folder") { ui.dialog = WsDialog.PickDelete },
        )

        setContent {
            LilyTheme(dark = state.theme.dark) {
                // Back from any other section goes to Work first. From Work, while tasks are running, it sends the app to the
                // background instead of closing it, because closing it would stop them.
                BackHandler(enabled = nav.section != Section.Work || queue.busy) {
                    if (nav.section != Section.Work) nav.section = Section.Work else this@EditorActivity.moveTaskToBack(true)
                }
                CompositionLocalProvider(LocalWorkspaceOps provides ops) {
                    val wide = LocalConfiguration.current.screenWidthDp >= 600
                    // While the keyboard is up the bottom bar steps aside, so typing never squeezes the screen between the two.
                    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    val onSelect: (Section) -> Unit = { section ->
                        // Tapping AI pops its drawer out. Tapping it again while it is showing closes the drawer.
                        if (section == Section.Ai) aiNav.menuOpen = nav.section != Section.Ai || !aiNav.menuOpen
                        nav.section = section
                        if (section == Section.Plugins) askTermuxPermission()
                    }
                    val badge: (Section) -> Int = { section -> if (section == Section.Chat) queue.needYou else 0 }

                    val sections: @Composable () -> Unit = {
                        when (nav.section) {
                            Section.Work -> Box(Modifier.fillMaxSize()) {
                                // The palette also lists the prompt commands of every plugin that is turned on.
                                val commands = remember(plugins.version) { extraCommands + promptCommands(plugins, session, nav) }
                                EditorScreen(state, commands)
                                state.largeFile?.let { big -> LargeFileViewer(big, state) { state.largeFile = null } }
                                AgentOverlay(session, ui, Modifier.align(Alignment.BottomEnd))
                                WorkspaceDialogs(ops, ui)
                                RemoteBanner(Modifier.align(Alignment.TopCenter))
                                RemoteDialogs(remoteUi)
                                GitDialog(git)
                            }
                            Section.Chat -> ChatScreen(chat, queue, onReview = {
                                session.startReview()
                                nav.section = Section.Work
                            })
                            Section.Browse -> BrowseScreen(browser)
                            Section.Plugins -> PluginsScreen(plugins)
                            Section.Ai -> AiHost(ai, aiNav)
                            Section.Settings -> SettingsScreen(this@EditorActivity, lifecycleScope, onSaved = { lily = null })
                        }
                    }

                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (wide) {
                            Row(Modifier.fillMaxSize()) {
                                AppSidebar(nav, onSelect, badge = badge)
                                Box(Modifier.weight(1f).fillMaxHeight()) { sections() }
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                // The bar below already clears the gesture area, so the screen above must not clear it a second time.
                                val above = if (keyboardUp) Modifier else Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                                Box(Modifier.weight(1f).fillMaxWidth().then(above)) { sections() }
                                if (!keyboardUp) AppBottomBar(nav, onSelect, badge = badge)
                            }
                        }
                    }
                    PluginDialogs(plugins)
                }
            }
        }
    }

    /**
     * Palette entries from plugins that are on. Running one sends its text, with the selection, file and language filled in, to Lily.
     * The reply and any proposed edits come back the usual way, for the person to read and accept.
     */
    private fun promptCommands(plugins: PluginServices, session: AgentSession, nav: ShellNav): List<ExtraCommand> =
        plugins.registry.list().filter { it.state.enabled }.flatMap { plugin ->
            plugin.manifest.promptCommands.map { command ->
                ExtraCommand("${plugin.manifest.name}: ${command.name}") {
                    val tab = state.current
                    val selection = tab?.value?.let { it.text.substring(it.selection.min, it.selection.max) }.orEmpty()
                    val path = tab?.path.orEmpty()
                    nav.section = Section.Work
                    lifecycleScope.launch { session.ask(fillPrompt(command.template, selection, path, path.substringAfterLast('.', ""))) }
                }
            }
        }

    /** Reads the font the person picked and hands it to the editor, which checks it and keeps a copy. */
    private suspend fun addFont(uri: Uri) {
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        val stream = withContext(Dispatchers.IO) { runCatching { contentResolver.openInputStream(uri) }.getOrNull() }
        if (stream == null) {
            state.message = "Could not open that file"
            return
        }
        state.installFont(name, stream)
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { return it }
            }
        }
        return uri.lastPathSegment ?: "font.ttf"
    }

    /** The app can be killed any time after it leaves the screen, so unsaved typing and the open tabs are written down now. */
    override fun onStop() {
        super.onStop()
        if (::state.isInitialized) {
            state.flushDrafts()
            state.saveSession()
        }
    }

    /** Leaving the editor for good closes the server connection. Being rebuilt (connect, disconnect) does not. */
    override fun onDestroy() {
        super.onDestroy()
        browse?.web?.destroy()
        // The queue dies with this screen, so the service has nothing left to keep alive. The saved tasks start again next time.
        TaskService.stop(this)
        if (isFinishing) RemoteHub.close()
    }

    private companion object {
        const val TERMUX_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    }
}
