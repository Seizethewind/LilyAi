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

        val session = AgentSession(state)
        // Lily is built the first time she is asked, from the router settings saved in Settings.
        val lily by lazy { LilyFactory.create(EncryptedSettingsStore(applicationContext).load(), session.bridge) }
        session.responder = { request ->
            withContext(Dispatchers.Default) { lily.handle(request) }
                .joinToString("\n\n") { it.output.trim() }
        }

        setContent {
            MaterialTheme(colorScheme = if (state.theme.dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    EditorScreen(state)
                    AgentOverlay(session, Modifier.align(Alignment.BottomEnd))
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
