package com.seizethewind.lilyai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.inference.LlamaEngine
import com.seizethewind.lilyai.model.ModelDownloadManager
import com.seizethewind.lilyai.model.ModelState
import com.seizethewind.lilyai.settings.EngineMode
import com.seizethewind.lilyai.settings.SettingsManager
import kotlinx.coroutines.launch

private const val GROQ = "groq"

@Composable
fun SettingsScreen(
    settings: SettingsManager,
    keyStore: ApiKeyStore,
    modelManager: ModelDownloadManager,
    localEngine: LlamaEngine,
    onBack: () -> Unit
) {
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(keyStore.has(GROQ)) }
    var saveError by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(settings.groqModel) }
    var mode by remember { mutableStateOf(settings.engineMode) }

    val modelState by modelManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val downloading = modelState is ModelState.Downloading

    // Keep the screen on while the model downloads so Android doesn't pause the network.
    DisposableEffect(downloading) {
        view.keepScreenOn = downloading
        onDispose { view.keepScreenOn = false }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        Text("Local model", style = MaterialTheme.typography.titleMedium)
        if (!LlamaEngine.isSupported) {
            Text(
                text = "This device can't run the local engine (it needs a 64-bit ARM phone).",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = "Qwen3-4B, about 2.5 GB. Runs fully offline once downloaded.",
            style = MaterialTheme.typography.bodyMedium
        )
        ModelSection(
            state = modelState,
            onStart = { modelManager.start() },
            onPause = { modelManager.cancel() },
            onDelete = {
                scope.launch {
                    localEngine.unload()
                    modelManager.delete()
                }
            }
        )

        Text("Answer with", style = MaterialTheme.typography.titleMedium)
        EngineOption(mode == EngineMode.AUTO, "Auto: local first, Groq if local fails") {
            mode = EngineMode.AUTO
            settings.engineMode = EngineMode.AUTO
        }
        EngineOption(mode == EngineMode.LOCAL, "Local only (nothing leaves the phone)") {
            mode = EngineMode.LOCAL
            settings.engineMode = EngineMode.LOCAL
        }
        EngineOption(mode == EngineMode.CLOUD, "Groq only") {
            mode = EngineMode.CLOUD
            settings.engineMode = EngineMode.CLOUD
        }

        Text("Groq API key", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (hasKey) "A key is saved on this device (encrypted)." else "No key saved yet.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (saveError) {
            Text(
                text = "Could not save the key on this device.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Paste Groq key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val saved = keyStore.set(GROQ, keyInput.trim())
                    saveError = !saved
                    if (saved) {
                        keyInput = ""
                        hasKey = true
                    }
                },
                enabled = keyInput.isNotBlank()
            ) { Text("Save key") }
            OutlinedButton(
                onClick = {
                    keyStore.clear(GROQ)
                    hasKey = false
                },
                enabled = hasKey
            ) { Text("Remove key") }
        }

        Text("Groq model", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = model,
            onValueChange = {
                model = it
                settings.groqModel = it.trim()
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ModelSection(
    state: ModelState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is ModelState.NotDownloaded -> {
                Text(
                    if (state.partialBytes > 0) {
                        "Partly downloaded (${mb(state.partialBytes)}). It will resume where it stopped."
                    } else {
                        "Not downloaded."
                    }
                )
                Button(onClick = onStart) {
                    Text(if (state.partialBytes > 0) "Resume download" else "Download model")
                }
            }
            is ModelState.Downloading -> {
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = state.downloaded.toFloat() / state.total.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${mb(state.downloaded)} of ${mb(state.total)}")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Starting...")
                }
                Text(
                    text = "Keep the app open while it downloads.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onPause) { Text("Pause") }
            }
            ModelState.Ready -> {
                Text("Ready. The local model is installed.")
                OutlinedButton(onClick = onDelete) { Text("Delete model") }
            }
            is ModelState.Error -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onStart) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun EngineOption(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

private fun mb(bytes: Long): String = "${bytes / (1024L * 1024L)} MB"
