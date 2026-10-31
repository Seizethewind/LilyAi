package ai.lily.app.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import ai.lily.app.lib.editor.DraftStore
import java.io.File

/** The Acode style code editor. Files live in the app's own `workspace` folder. */
class EditorActivity : ComponentActivity() {
    private lateinit var state: EditorState

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = File(filesDir, "workspace").apply { mkdirs() }
        val drafts = DraftStore(File(filesDir, "drafts"))
        state = EditorState(root, drafts, EditorPrefs(applicationContext))
        setContent {
            MaterialTheme(colorScheme = if (state.theme.dark) darkColorScheme() else lightColorScheme()) {
                EditorScreen(state)
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
