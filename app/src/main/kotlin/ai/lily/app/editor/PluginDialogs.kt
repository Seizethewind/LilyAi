package ai.lily.app.editor

import ai.lily.app.plugins.InstallResult
import ai.lily.app.plugins.InstalledPlugin
import ai.lily.app.plugins.Permission
import ai.lily.app.plugins.PluginServices
import ai.lily.app.plugins.PromptGate
import ai.lily.app.plugins.Visible
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.ConfirmDialog
import ai.lily.app.ui.EmptyState
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilySheet
import ai.lily.app.ui.LilySwitch
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.ListRow
import ai.lily.app.ui.ScreenFrame
import ai.lily.app.ui.ScreenHeader
import ai.lily.app.ui.Tone
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** The "run this command?" question a plugin tool asks before it does anything. It can appear over any section. */
@Composable
fun PluginDialogs(services: PluginServices) {
    services.gate.pending?.let { ConfirmRunDialog(it, services.gate) }
}

@Composable
private fun ConfirmRunDialog(pending: PromptGate.Pending, gate: PromptGate) {
    val r = pending.request
    val c = LilyUi.colors
    AlertDialog(
        onDismissRequest = { gate.answer(false) },
        containerColor = c.raised,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Run this command?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${r.pluginName} wants to use ${r.toolName} in ${r.targetPackage}. Nothing runs unless you approve.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textDim,
                )
                for ((key, value) in r.details) {
                    Column {
                        Text(key, style = MaterialTheme.typography.labelMedium, color = c.textDim)
                        Text(
                            value,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { LilyButton("Run", onClick = { gate.answer(true) }) },
        dismissButton = { LilyButton("Don't run", onClick = { gate.answer(false) }, kind = ButtonKind.Quiet) },
    )
}

/** The Plugins section: every installed plugin with its switches, a catalog to install from, and a way to paste one in. */
@Composable
fun PluginsScreen(services: PluginServices, modifier: Modifier = Modifier) {
    val version = services.version
    val plugins = remember(version) { services.registry.list() }
    var adding by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    ScreenFrame(modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { ScreenHeader("Plugins", subtitle = "A plugin is a plain manifest. Nothing runs until you turn it on and allow it.") }
            item {
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LilyButton("Browse catalog", onClick = { catalog = true }, kind = ButtonKind.Tonal)
                    LilyButton("Add from JSON", onClick = { adding = true }, kind = ButtonKind.Quiet)
                }
            }
            note?.let { message ->
                item {
                    Banner(message, Tone.Petal, Modifier.padding(horizontal = 20.dp, vertical = 12.dp), actionLabel = "Dismiss", onAction = { note = null })
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            if (plugins.isEmpty()) {
                item {
                    EmptyState(
                        title = "No plugins installed",
                        message = "Browse the catalog to add one, or paste a manifest you trust.",
                        actionLabel = "Browse catalog",
                        onAction = { catalog = true },
                    )
                }
            }
            items(plugins, key = { p -> p.manifest.id }) { plugin ->
                Column {
                    Hairline(Modifier.padding(horizontal = 20.dp))
                    PluginItem(plugin, services) { message -> note = message }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (catalog) LilySheet("Plugin catalog", onDismiss = { catalog = false }) { CatalogPanel(services) { message -> note = message } }
    if (adding) {
        AddPluginSheet(services, onDismiss = { adding = false }) { message ->
            note = message
            adding = false
        }
    }
}

@Composable
private fun SmallHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LilyUi.colors.textDim)
}

/** One plugin: its name and switch always show; tapping the row opens what it can do and what it is allowed to. */
@Composable
private fun PluginItem(plugin: InstalledPlugin, services: PluginServices, onNote: (String) -> Unit) {
    val manifest = plugin.manifest
    val c = LilyUi.colors
    var open by remember(manifest.id) { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column {
        ListRow(
            title = "${manifest.name} ${manifest.version}",
            subtitle = manifest.description,
            onClick = { open = !open },
            trailing = { LilySwitch(plugin.state.enabled, { on -> services.setEnabled(manifest.id, on) }) },
        )
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (manifest.promptCommands.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallHeading("Commands in the palette while the plugin is on")
                        for (command in manifest.promptCommands) {
                            Column {
                                Text(command.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                                // The text sent to Lily, with hidden characters made visible, so what you read is what is sent.
                                Text(
                                    Visible.escape(command.template),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = c.textDim,
                                    maxLines = 4,
                                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.sunken).padding(12.dp),
                                )
                            }
                        }
                    }
                }
                if (manifest.permissions.isNotEmpty()) {
                    Column {
                        SmallHeading("Allow")
                        for (permission in manifest.permissions) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Start a service in ${permissionLabel(permission)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                )
                                LilySwitch(permission.key in plugin.state.granted, { on -> services.setGranted(manifest.id, permission.key, on) })
                            }
                        }
                    }
                }
                if (manifest.tools.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SmallHeading("Tools")
                        for (tool in manifest.tools) {
                            val allowed = Permission.ExternalApp(tool.action.packageName).key in plugin.state.granted
                            Column {
                                Text(tool.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = c.textDim)
                                if (allowed) {
                                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("Run without asking each time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).padding(end = 12.dp))
                                        LilySwitch(tool.name in plugin.state.autoApprove, { on -> services.setAutoApprove(manifest.id, tool.name, on) })
                                    }
                                } else {
                                    Text("Turn on the permission above to use this tool.", style = MaterialTheme.typography.bodySmall, color = c.textFaint, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }
                LilyButton("Remove plugin", onClick = { confirmRemove = true }, kind = ButtonKind.Danger)
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove ${manifest.name}?",
            message = "Its settings and allowances are deleted. You can add it again later.",
            confirmLabel = "Remove",
            danger = true,
            onConfirm = {
                confirmRemove = false
                services.uninstall(manifest.id)
                onNote("${manifest.name} removed")
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun AddPluginSheet(services: PluginServices, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    LilySheet("Add plugin", onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LilyTextField(
                value = text,
                onValueChange = {
                    text = it
                    errors = emptyList()
                },
                label = "Plugin manifest (JSON)",
                helper = "It is checked first, then installed turned off with nothing allowed.",
                mono = true,
                singleLine = false,
                minLines = 6,
            )
            for (error in errors) Banner(error, Tone.Coral)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LilyButton(
                    "Install",
                    enabled = text.isNotBlank(),
                    onClick = {
                        when (val result = services.install(text)) {
                            is InstallResult.Rejected -> errors = result.errors
                            is InstallResult.Installed -> onDone(summary(result))
                        }
                    },
                )
                LilyButton("Cancel", onClick = onDismiss, kind = ButtonKind.Quiet)
            }
        }
    }
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
