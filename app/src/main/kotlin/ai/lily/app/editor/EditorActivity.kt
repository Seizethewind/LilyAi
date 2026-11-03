package ai.lily.app.editor

import ai.lily.app.LilyFactory
import ai.lily.app.core.Lily
import ai.lily.app.lib.editor.DraftStore
import ai.lily.app.plugins.PluginServices
import ai.lily.app.remote.RemoteHub
import ai.lily.app.remote.RemoteStore
import ai.lily.app.settings.EncryptedSettingsStore
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Acode style code editor, with Lily's coding and file workers connected to it. Files live in the app's own `workspace` folder,
 * or, while a remote connection is open, in the local copy of that server folder.
 */
class EditorActivity : ComponentActivity() {
    private lateinit var state: EditorState

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

        // Plugins: installed ones are kept in the app's `plugins` folder. The bundled ones (Termux) arrive off, with nothing allowed.
        val plugins = PluginServices(applicationContext).also { it.installBundled() }
        val pluginUi = PluginUi()

        // Lily is built the first time she is asked, and again when the person changes a plugin, so a plugin turned on a moment ago
        // gives her its tools. The project folder lets her coding worker find relevant files beyond the open tabs.
        var lily: Lily? = null
        var builtFor = -1
        fun currentLily(): Lily {
            val wanted = plugins.version
            return lily.takeIf { builtFor == wanted } ?: LilyFactory.create(
                EncryptedSettingsStore(applicationContext).load(), session.bridge, root, plugins.host,
            ).also {
                lily = it
                builtFor = wanted
            }
        }
        session.responder = { request ->
            withContext(Dispatchers.Default) { currentLily().handle(request) }
                .joinToString("\n\n") { it.output.trim() }
        }

        // Extra entries in the command palette.
        val extraCommands = listOf(
            ExtraCommand("Ask Lily") { ui.ask = true },
            ExtraCommand("Remote workspaces") { remoteUi.manager = true },
            ExtraCommand("Plugins") { pluginUi.open = true },
            ExtraCommand("New folder") { ui.dialog = WsDialog.NewFolder },
            ExtraCommand("Rename or move file or folder") { ui.dialog = WsDialog.PickRename },
            ExtraCommand("Delete file or folder") { ui.dialog = WsDialog.PickDelete },
        )

        setContent {
            MaterialTheme(colorScheme = if (state.theme.dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalWorkspaceOps provides ops) {
                    Box(Modifier.fillMaxSize()) {
                        EditorScreen(state, extraCommands)
                        state.largeFile?.let { big -> LargeFileViewer(big, state) { state.largeFile = null } }
                        AgentOverlay(session, ui, Modifier.align(Alignment.BottomEnd))
                        WorkspaceDialogs(ops, ui)
                        PluginDialogs(plugins, pluginUi)
                        RemoteBanner(Modifier.align(Alignment.TopCenter))
                        RemoteDialogs(remoteUi)
                    }
                }
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

    /** Leaving the editor for good closes the server connection. Being rebuilt (connect, disconnect, rotate) does not. */
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) RemoteHub.close()
    }
}
