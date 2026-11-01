package ai.lily.app.editor

import ai.lily.app.LilyFactory
import ai.lily.app.lib.editor.DraftStore
import ai.lily.app.settings.EncryptedSettingsStore
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The Acode style code editor, with Lily's coding and file workers connected to it. Files live in the app's own `workspace` folder. */
class EditorActivity : ComponentActivity() {
    private lateinit var state: EditorState

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = File(filesDir, "workspace").apply { mkdirs() }
        val drafts = DraftStore(File(filesDir, "drafts"))
        state = EditorState(root, drafts, EditorPrefs(applicationContext))

        val ops = WorkspaceOps(state, drafts)
        val ui = EditorUi()
        val session = AgentSession(state)
        // Lily is built the first time she is asked, from the router settings saved in Settings. The project folder lets her coding
        // worker find relevant files beyond the open tabs.
        val lily by lazy { LilyFactory.create(EncryptedSettingsStore(applicationContext).load(), session.bridge, root) }
        session.responder = { request ->
            withContext(Dispatchers.Default) { lily.handle(request) }
                .joinToString("\n\n") { it.output.trim() }
        }

        // Extra entries in the command palette.
        val extraCommands = listOf(
            ExtraCommand("Ask Lily") { ui.ask = true },
            ExtraCommand("New folder") { ui.dialog = WsDialog.NewFolder },
            ExtraCommand("Rename or move file or folder") { ui.dialog = WsDialog.PickRename },
            ExtraCommand("Delete file or folder") { ui.dialog = WsDialog.PickDelete },
        )

        setContent {
            MaterialTheme(colorScheme = if (state.theme.dark) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalWorkspaceOps provides ops) {
                    Box(Modifier.fillMaxSize()) {
                        EditorScreen(state, extraCommands)
                        AgentOverlay(session, ui, Modifier.align(Alignment.BottomEnd))
                        WorkspaceDialogs(ops, ui)
                    }
                }
            }
        }
    }

    /** The app can be killed any time after it leaves the screen, so unsaved typing and the open tabs are written down now. */
    override fun onStop() {
        super.onStop()
        if (::state.isInitialized) {
            state.flushDrafts()
            state.saveSession()
        }
    }
}
