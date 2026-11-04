package ai.lily.app

import ai.lily.app.editor.EditorActivity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** The launcher entry. The app lives in [EditorActivity], which hosts the sidebar and its four sections. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, EditorActivity::class.java))
        finish()
    }
}
