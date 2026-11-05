package ai.lily.app.editor

import ai.lily.app.plugins.PluginServices
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Git control for the person. It runs the same `lily.termux.git` tools Lily's workers use, through
 * [PluginServices.run], so the checks (plugin on, Termux allowed) and the approval question are the same for both.
 */
class GitState(context: Context, private val plugins: PluginServices, private val scope: CoroutineScope) {
    private val prefs = context.applicationContext.getSharedPreferences("lily_git", Context.MODE_PRIVATE)

    var open by mutableStateOf(false)
    var repo by mutableStateOf(prefs.getString(REPO, DEFAULT_REPO).orEmpty())
    var message by mutableStateOf("")
    var output by mutableStateOf("")
    var busy by mutableStateOf(false)

    fun run(tool: String, vararg extra: Pair<String, String>) {
        if (busy) return
        busy = true
        output = ""
        prefs.edit().putString(REPO, repo).apply()
        scope.launch {
            try {
                output = plugins.run(ID, tool, mapOf("repo" to repo.trim()) + extra)
                if (tool == "git_commit" && output.startsWith("exit=0")) message = ""
            } finally {
                busy = false
            }
        }
    }

    companion object {
        const val ID = "lily.termux.git"
        private const val REPO = "repo"
        private const val DEFAULT_REPO = "/data/data/com.termux/files/home"
    }
}

@Composable
fun GitDialog(git: GitState) {
    if (!git.open) return
    AlertDialog(
        onDismissRequest = { git.open = false },
        title = { Text("Git control") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Runs in Termux. Turn on Git control in Plugins and allow Termux first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = git.repo,
                    onValueChange = { git.repo = it },
                    label = { Text("Repo folder in Termux") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GitButton("Status", git, Modifier.weight(1f)) { git.run("git_status") }
                    GitButton("Diff", git, Modifier.weight(1f)) { git.run("git_diff") }
                    GitButton("Log", git, Modifier.weight(1f)) { git.run("git_log") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GitButton("Pull", git, Modifier.weight(1f)) { git.run("git_pull") }
                    GitButton("Stage all", git, Modifier.weight(1f)) { git.run("git_add") }
                    GitButton("Push", git, Modifier.weight(1f)) { git.run("git_push") }
                }
                OutlinedTextField(
                    value = git.message,
                    onValueChange = { git.message = it },
                    label = { Text("Commit message") },
                    modifier = Modifier.fillMaxWidth(),
                )
                GitButton("Commit staged", git, Modifier.fillMaxWidth()) { git.run("git_commit", "message" to git.message) }
                Box(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                    SelectionContainer {
                        Text(
                            if (git.busy) "Running..." else git.output,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { git.open = false }) { Text("Close") } },
    )
}

@Composable
private fun GitButton(label: String, git: GitState, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !git.busy, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Text(label, maxLines = 1)
    }
}
