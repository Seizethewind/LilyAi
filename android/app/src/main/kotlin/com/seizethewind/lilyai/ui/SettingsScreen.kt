package com.seizethewind.lilyai.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.settings.SettingsManager

private const val GROQ = "groq"

@Composable
fun SettingsScreen(settings: SettingsManager, keyStore: ApiKeyStore, onBack: () -> Unit) {
    var keyInput by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(keyStore.has(GROQ)) }
    var saveError by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(settings.groqModel) }
    var localOnly by remember { mutableStateOf(settings.localOnly) }

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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Local only (no cloud)", modifier = Modifier.weight(1f))
            Switch(
                checked = localOnly,
                onCheckedChange = {
                    localOnly = it
                    settings.localOnly = it
                }
            )
        }
        Text(
            text = "The local model arrives in a later update. Until then, chat needs a Groq key.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
