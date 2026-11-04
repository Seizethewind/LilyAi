package ai.lily.app.editor

import ai.lily.app.plugins.InstallResult
import ai.lily.app.plugins.InstalledPlugin
import ai.lily.app.plugins.Permission
import ai.lily.app.plugins.PluginServices
import ai.lily.app.plugins.PromptGate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The "run this command?" question a plugin tool asks before it does anything. It can appear over any section. */
@Composable
fun PluginDialogs(services: PluginServices) {
    services.gate.pending?.let { ConfirmRunDialog(it, services.gate) }
}

@Composable
private fun ConfirmRunDialog(pending: PromptGate.Pending, gate: PromptGate) {
    val r = pending.request
    AlertDialog(
        onDismissRequest = { gate.answer(false) },
        title = { Text("Run this command?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${r.pluginName} wants to use ${r.toolName} in ${r.targetPackage}. Nothing runs unless you approve.")
                Spacer(Modifier.height(8.dp))
                for ((key, value) in r.details) {
                    Text(key, style = MaterialTheme.typography.labelSmall)
                    Text(value, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { gate.answer(true) }) { Text("Run") } },
        dismissButton = { TextButton(onClick = { gate.answer(false) }) { Text("Don't run") } },
    )
}

/** The Plugins section: every installed plugin with its switches, and a way to add one. */
@Composable
fun PluginsScreen(services: PluginServices, modifier: Modifier = Modifier) {
    val version = services.version
    val plugins = remember(version) { services.registry.list() }
    var adding by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LazyColumn(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Plugins", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Text("Add from JSON") }
            }
        }
        note?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (plugins.isEmpty()) item { Text("No plugins installed.") }
        items(plugins, key = { p -> p.manifest.id }) { plugin ->
            PluginCard(plugin, services) { message -> note = message }
        }
    }
    if (adding) {
        AddPluginDialog(services, onDismiss = { adding = false }) { message ->
            note = message
            adding = false
        }
    }
}

@Composable
private fun PluginCard(plugin: InstalledPlugin, services: PluginServices, onNote: (String) -> Unit) {
    val manifest = plugin.manifest
    var confirmRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${manifest.name} ${manifest.version}", style = MaterialTheme.typography.titleSmall)
                Text(manifest.description, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = plugin.state.enabled, onCheckedChange = { on -> services.setEnabled(manifest.id, on) })
        }

        if (manifest.permissions.isNotEmpty()) {
            Text("Allow", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            for (permission in manifest.permissions) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start a service in ${permissionLabel(permission)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = permission.key in plugin.state.granted,
                        onCheckedChange = { on -> services.setGranted(manifest.id, permission.key, on) },
                    )
                }
            }
        }

        if (manifest.tools.isNotEmpty()) {
            Text("Tools", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            for (tool in manifest.tools) {
                val allowed = Permission.ExternalApp(tool.action.packageName).key in plugin.state.granted
                Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                Text(tool.description, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = tool.name in plugin.state.autoApprove,
                        enabled = allowed,
                        onCheckedChange = { on -> services.setAutoApprove(manifest.id, tool.name, on) },
                    )
                    Text(
                        if (allowed) "Run without asking each time" else "Allow the permission above to use this tool",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${manifest.name}?") },
            text = { Text("Its settings and allowances are deleted. You can add it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    services.uninstall(manifest.id)
                    onNote("${manifest.name} removed")
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddPluginDialog(services: PluginServices, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add plugin") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Paste the plugin's JSON manifest. It is checked first, and installs turned off with nothing allowed.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = text,
                    onValueChange = {
                        text = it
                        errors = emptyList()
                    },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                )
                for (error in errors) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val result = services.install(text)) {
                    is InstallResult.Rejected -> errors = result.errors
                    is InstallResult.Installed -> onDone(summary(result))
                }
            }) { Text("Install") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun permissionLabel(permission: Permission): String = when (permission) {
    is Permission.ExternalApp -> permission.packageName
}

private fun summary(result: InstallResult.Installed): String = buildString {
    val name = result.plugin.manifest.name
    if (result.updated) append("$name updated.") else append("$name installed. It is off and nothing is allowed yet.")
    if (result.droppedGrants.isNotEmpty()) append(" No longer allowed: ${result.droppedGrants.joinToString()}.")
    if (result.clearedAutoApprove.isNotEmpty()) append(" Asks again for: ${result.clearedAutoApprove.joinToString()}.")
}
