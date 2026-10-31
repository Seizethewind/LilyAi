package ai.lily.app.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import java.io.File

/** The Acode style code editor. Files live in the app's own `workspace` folder. */
class EditorActivity : ComponentActivity() {
    private lateinit var state: EditorState

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = File(filesDir, "workspace").apply { mkdirs() }
        state = EditorState(root)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                EditorScreen(state)
            }
        }
    }
}
