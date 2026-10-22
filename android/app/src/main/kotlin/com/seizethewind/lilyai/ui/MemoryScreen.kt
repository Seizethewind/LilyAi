package com.seizethewind.lilyai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.seizethewind.lilyai.memory.MemoryManager
import com.seizethewind.lilyai.settings.SettingsManager

@Composable
fun MemoryScreen(
    memoryManager: MemoryManager,
    settings: SettingsManager,
    onOpenCloud: () -> Unit,
    onBack: () -> Unit
) {
    var memories by remember { mutableStateOf(memoryManager.all()) }
    var input by remember { mutableStateOf("") }
    var share by remember { mutableStateOf(settings.shareMemoryWithCloud) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Memories", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            text = "Things LilyAi keeps because you asked. Say \"remember that ...\" in chat, or add one here. Nothing is saved on its own.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add a memory") },
                maxLines = 3
            )
            Button(
                onClick = {
                    memoryManager.remember(input)
                    input = ""
                    memories = memoryManager.all()
                },
                enabled = input.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Add") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Share memories with Groq (cloud)", modifier = Modifier.weight(1f))
            Switch(
                checked = share,
                onCheckedChange = {
                    share = it
                    settings.shareMemoryWithCloud = it
                }
            )
        }
        Text(
            text = "Off by default. The local model always sees your memories. When this is off, they are never sent to Groq.",
            style = MaterialTheme.typography.bodySmall
        )

        TextButton(onClick = onOpenCloud) { Text("Cloud sync and backup") }

        if (memories.isEmpty()) {
            Text("Nothing saved yet.")
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(memories, key = { it.id }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.text, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            memoryManager.forget(item.id)
                            memories = memoryManager.all()
                        }
                    ) { Text("Delete") }
                }
            }
        }
    }
}
