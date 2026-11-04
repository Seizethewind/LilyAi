package ai.lily.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The settings form on its own screen. The app itself shows the same form in its Settings section ([SettingsPanel]). */
class SettingsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SettingsPanel(this, findViewById(android.R.id.content), scope) { }.bind()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
