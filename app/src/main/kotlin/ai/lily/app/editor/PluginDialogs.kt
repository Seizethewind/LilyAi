package ai.lily.app.editor

import ai.lily.app.plugins.InstallResult
import ai.lily.app.plugins.InstalledPlugin
import ai.lily.app.plugins.Permission
import ai.lily.app.plugins.PluginServices
import ai.lily.app.plugins.PromptGate
import ai.lily.app.plugins.SettingSpec
import ai.lily.app.plugins.Visible
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilySheet
import ai.lily.app.ui.LilySwitch
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.Tone
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
        title = { Text(if (r.isHttp) "Send this request?" else "Run this command?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (r.isHttp) {
                        "${r.pluginName} wants to send a request to ${r.targetPackage} with ${r.toolName}. " +
                            (if (r.changes) "It changes something there. " else "") +
                            "Secrets are hidden here and never shown to Lily. Nothing is sent unless you approve."
                    } else {
                        "${r.pluginName} wants to use ${r.toolName} in ${r.targetPackage}. Nothing runs unless you approve."
                    },
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
        confirmButton = { LilyButton(if (r.isHttp) "Send" else "Run", onClick = { gate.answer(true) }) },
        dismissButton = { LilyButton(if (r.isHttp) "Don't send" else "Don't run", onClick = { gate.answer(false) }, kind = ButtonKind.Quiet) },
    )
}

@Composable
private fun SmallHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = LilyUi.colors.textDim)
}

/** What an installed plugin can do and what it is allowed to, with its switches. Shown on the plugin's detail page. */
@Composable
internal fun PluginDetailBody(plugin: InstalledPlugin, services: PluginServices) {
    val manifest = plugin.manifest
    val c = LilyUi.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Turned on", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).padding(end = 12.dp))
            LilySwitch(plugin.state.enabled, { on -> services.setEnabled(manifest.id, on) })
        }
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
                            permissionText(permission),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        LilySwitch(permission.key in plugin.state.granted, { on -> services.setGranted(manifest.id, permission.key, on) })
                    }
                }
            }
        }
        if (manifest.settings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallHeading("Settings")
                Text(
                    "Saved on this phone, encrypted. A secret is never shown again and never given to Lily; a tool only sends it when you approve a request.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textDim,
                )
                for (spec in manifest.settings) SettingField(manifest.id, spec, services)
            }
        }
        if (manifest.tools.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallHeading("Tools")
                for (tool in manifest.tools) {
                    val allowed = tool.action.permissionKey in plugin.state.granted
                    Column {
                        Text(tool.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                        Text(tool.description, style = MaterialTheme.typography.bodySmall, color = c.textDim)
                        if (allowed && tool.action.alwaysAsks) {
                            Text("Always asks before it sends, because it changes something.", style = MaterialTheme.typography.bodySmall, color = c.textDim, modifier = Modifier.padding(top = 4.dp))
                        } else if (allowed) {
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
    }
}

@Composable
internal fun AddPluginSheet(services: PluginServices, onDismiss: () -> Unit, onDone: (String) -> Unit) {
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

private fun permissionText(permission: Permission): String = when (permission) {
    is Permission.ExternalApp -> "Start a service in ${permission.packageName}"
    is Permission.Http -> "Send https requests to ${permission.host}${permission.pathPrefix}"
}

/** One value the person fills in. A saved secret is never shown: the field starts empty and a new value replaces it. */
@Composable
private fun SettingField(pluginId: String, spec: SettingSpec, services: PluginServices) {
    var text by remember(pluginId, spec.name) { mutableStateOf(services.plainSetting(pluginId, spec)) }
    var error by remember(pluginId, spec.name) { mutableStateOf<String?>(null) }
    val saved = remember(services.version, pluginId, spec.name) { services.hasSetting(pluginId, spec.name) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LilyTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            label = if (spec.required) spec.label else "${spec.label} (optional)",
            error = error,
            helper = when {
                spec.secret && saved -> "A value is saved and hidden. Type a new one to replace it."
                spec.description.isNotEmpty() -> spec.description
                else -> null
            },
            secret = spec.secret,
            mono = spec.secret,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LilyButton(
                "Save",
                enabled = text.isNotBlank(),
                kind = ButtonKind.Tonal,
                onClick = {
                    val problem = spec.problemWith(text)
                    if (problem != null) {
                        error = problem
                    } else {
                        services.saveSetting(pluginId, spec, text)
                        if (spec.secret) text = ""
                    }
                },
            )
            if (saved) {
                LilyButton(
                    "Clear",
                    kind = ButtonKind.Quiet,
                    onClick = {
                        services.clearSetting(pluginId, spec.name)
                        text = ""
                    },
                )
            }
        }
    }
}

private fun summary(result: InstallResult.Installed): String = buildString {
    val name = result.plugin.manifest.name
    if (result.updated) append("$name updated.") else append("$name installed. It is off and nothing is allowed yet.")
    if (result.droppedGrants.isNotEmpty()) append(" No longer allowed: ${result.droppedGrants.joinToString()}.")
    if (result.clearedAutoApprove.isNotEmpty()) append(" Asks again for: ${result.clearedAutoApprove.joinToString()}.")
}
