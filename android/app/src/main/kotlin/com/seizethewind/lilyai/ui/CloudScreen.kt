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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.seizethewind.lilyai.api.ApiKeyStore
import com.seizethewind.lilyai.memory.CloudSync
import com.seizethewind.lilyai.memory.SyncStatus
import com.seizethewind.lilyai.settings.SettingsManager
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun CloudScreen(
    keyStore: ApiKeyStore,
    cloudSync: CloudSync,
    settings: SettingsManager,
    onBack: () -> Unit
) {
    var refresh by remember { mutableStateOf(0) }
    var memoryUrl by remember { mutableStateOf("") }
    var memoryToken by remember { mutableStateOf("") }
    var backupUrl by remember { mutableStateOf("") }
    var backupToken by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    val status by cloudSync.status.collectAsState()
    val scope = rememberCoroutineScope()
    val busy = status is SyncStatus.Working

    val memoryReady = remember(refresh) { cloudSync.isMemoryConfigured() }
    val backupReady = remember(refresh) { cloudSync.isBackupConfigured() }
    val hasPassphrase = remember(refresh) { keyStore.has(CloudSync.ID_PASSPHRASE) }

    fun save(id: String, value: String) {
        if (value.isNotBlank()) keyStore.set(id, value.trim())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Cloud sync and backup", style = MaterialTheme.typography.titleLarge)
        }

        Text(
            text = "Uses two Turso databases: one keeps your memories in sync, the other holds backup files. Everything is encrypted on the phone before it is uploaded.",
            style = MaterialTheme.typography.bodyMedium
        )

        Text("Passphrase", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (hasPassphrase) "A passphrase is saved on this device." else "No passphrase saved yet.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Write it down somewhere safe. If you lose it, the cloud data cannot be recovered, and you need the same passphrase to restore on a new phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )

        Text("Memory database", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (memoryReady) "Memory database is set up." else "Not set up yet.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = memoryUrl,
            onValueChange = { memoryUrl = it },
            label = { Text("Database URL (libsql://...)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = memoryToken,
            onValueChange = { memoryToken = it },
            label = { Text("Auth token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Text("Backup database", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (backupReady) "Backup database is set up." else "Not set up yet.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = backupUrl,
            onValueChange = { backupUrl = it },
            label = { Text("Database URL (libsql://...)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = backupToken,
            onValueChange = { backupToken = it },
            label = { Text("Auth token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    save(CloudSync.ID_PASSPHRASE, passphrase)
                    save(CloudSync.ID_MEMORY_URL, memoryUrl)
                    save(CloudSync.ID_MEMORY_TOKEN, memoryToken)
                    save(CloudSync.ID_BACKUP_URL, backupUrl)
                    save(CloudSync.ID_BACKUP_TOKEN, backupToken)
                    passphrase = ""
                    memoryUrl = ""
                    memoryToken = ""
                    backupUrl = ""
                    backupToken = ""
                    refresh++
                },
                enabled = passphrase.isNotBlank() || memoryUrl.isNotBlank() || memoryToken.isNotBlank() ||
                    backupUrl.isNotBlank() || backupToken.isNotBlank()
            ) { Text("Save") }
            OutlinedButton(
                onClick = {
                    listOf(
                        CloudSync.ID_PASSPHRASE,
                        CloudSync.ID_MEMORY_URL,
                        CloudSync.ID_MEMORY_TOKEN,
                        CloudSync.ID_BACKUP_URL,
                        CloudSync.ID_BACKUP_TOKEN
                    ).forEach { keyStore.clear(it) }
                    refresh++
                }
            ) { Text("Remove all") }
        }

        Text("Actions", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.launch { cloudSync.syncNow() } },
                enabled = memoryReady && !busy
            ) { Text("Sync now") }
            OutlinedButton(
                onClick = { scope.launch { cloudSync.backupNow() } },
                enabled = backupReady && !busy
            ) { Text("Back up now") }
        }
        OutlinedButton(
            onClick = { scope.launch { cloudSync.restoreLatest() } },
            enabled = backupReady && !busy
        ) { Text("Restore latest backup") }

        when (val s = status) {
            SyncStatus.Idle -> {}
            SyncStatus.Working -> Text("Working...", style = MaterialTheme.typography.bodyMedium)
            is SyncStatus.Done -> Text(s.message, style = MaterialTheme.typography.bodyMedium)
            is SyncStatus.Failed -> Text(
                text = s.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        val lastBackup = remember(status) { settings.lastBackupAt }
        if (lastBackup > 0L) {
            Text(
                text = "Last backup: " + DateFormat.getDateTimeInstance().format(Date(lastBackup)),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
