package ai.lily.app.editor

import ai.lily.app.remote.ConflictKind
import ai.lily.app.remote.RemoteHub
import ai.lily.app.remote.RemoteProfile
import ai.lily.app.remote.RemoteProfileCheck
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every remote workspace screen: the list of connections, the form, the questions asked while connecting, and conflicts. */
@Composable
fun RemoteDialogs(ui: RemoteUi) {
    if (ui.manager) ManagerDialog(ui)
    ui.editing?.let { ProfileDialog(it, ui) }
    ui.passwordAsk?.let { PasswordDialog(it) }
    ui.hostKeyAsk?.let { HostKeyDialog(it) }
    ui.ftpAsk?.let { FtpDialog(it) }
    ui.conflict?.let { ConflictDialog(it, ui) }
    ui.busy?.let { BusyDialog(it) }
    ui.notice?.let { text ->
        AlertDialog(
            onDismissRequest = { ui.notice = null },
            title = { Text("Remote workspace") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { ui.notice = null }) { Text("OK") } },
        )
    }
}

/** Shown for as long as the connection is plain FTP, so the person always knows their files are not encrypted. */
@Composable
fun RemoteBanner(modifier: Modifier = Modifier) {
    val banner = RemoteHub.active?.session?.banner ?: return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth()) {
        Text(
            banner,
            modifier = Modifier.padding(6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun ManagerDialog(ui: RemoteUi) {
    val connected = ui.active
    AlertDialog(
        onDismissRequest = { ui.manager = false },
        title = { Text("Remote workspaces") },
        text = {
            LazyColumn(Modifier.fillMaxWidth()) {
                if (connected != null) {
                    item {
                        Text(
                            "Connected to ${connected.profile.name} over ${connected.session.protocol.label}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row {
                            TextButton(onClick = { ui.pull() }) { Text("Get server changes") }
                            TextButton(onClick = { ui.disconnect() }) { Text("Disconnect") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (ui.profiles.isEmpty()) item { Text("No saved connections yet.") }
                items(ui.profiles, key = { p -> p.id }) { profile ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleSmall)
                        Text("${profile.user}@${profile.host}  ${profile.rootPath}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { ui.connect(profile) }) { Text("Connect") }
                            TextButton(onClick = { ui.editing = profile }) { Text("Edit") }
                            TextButton(onClick = { ui.deleteProfile(profile) }) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { ui.manager = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { ui.newProfile() }) { Text("New connection") } },
    )
}

@Composable
private fun ProfileDialog(start: RemoteProfile, ui: RemoteUi) {
    var name by remember(start.id) { mutableStateOf(start.name) }
    var host by remember(start.id) { mutableStateOf(start.host) }
    var user by remember(start.id) { mutableStateOf(start.user) }
    var sftpPort by remember(start.id) { mutableStateOf(start.sftpPort.toString()) }
    var ftpPort by remember(start.id) { mutableStateOf(start.ftpPort.toString()) }
    var root by remember(start.id) { mutableStateOf(start.rootPath) }
    var tryFtps by remember(start.id) { mutableStateOf(start.tryFtps) }
    var allowFtp by remember(start.id) { mutableStateOf(start.allowFtpFallback) }
    var problems by remember(start.id) { mutableStateOf<List<String>>(emptyList()) }

    AlertDialog(
        onDismissRequest = { ui.editing = null },
        title = { Text(if (start.name.isEmpty()) "New connection" else "Edit connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                field("Name", name) { name = it }
                field("Server address (example.com)", host) { host = it }
                field("User name", user) { user = it }
                field("SFTP port", sftpPort, number = true) { sftpPort = it }
                field("FTP / FTPS port", ftpPort, number = true) { ftpPort = it }
                field("Folder on the server (starts with /)", root) { root = it }
                Spacer(Modifier.height(8.dp))
                Text("SFTP is always tried first.", style = MaterialTheme.typography.bodySmall)
                switchRow("Then try FTPS (encrypted FTP)", tryFtps) { tryFtps = it }
                switchRow("Allow plain FTP as a last resort (asks every time)", allowFtp) { allowFtp = it }
                for (p in problems) Text(p, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val built = RemoteProfile(
                    id = start.id,
                    name = name.trim(),
                    host = host.trim(),
                    user = user.trim(),
                    sftpPort = sftpPort.trim().toIntOrNull() ?: 0,
                    ftpPort = ftpPort.trim().toIntOrNull() ?: 0,
                    rootPath = root.trim(),
                    tryFtps = tryFtps,
                    allowFtpFallback = allowFtp,
                )
                problems = RemoteProfileCheck.problems(built)
                if (problems.isEmpty()) ui.saveProfile(built)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { ui.editing = null }) { Text("Cancel") } },
    )
}

@Composable
private fun field(label: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

@Composable
private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PasswordDialog(ask: PasswordAsk) {
    var password by remember(ask) { mutableStateOf("") }
    var keep by remember(ask) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { ask.answer.complete(null) },
        title = { Text("Password for ${ask.profile.user}@${ask.profile.host}") },
        text = {
            Column {
                if (ask.ftp) {
                    Text("Plain FTP is not encrypted. Type the password for this connection only; it is not saved.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                }
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!ask.ftp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = keep, onCheckedChange = { keep = it })
                        Text("Remember it, encrypted on this phone (secure connections only)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { ask.answer.complete(PasswordAnswer(password, keep && !ask.ftp)) }) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = { ask.answer.complete(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun HostKeyDialog(ask: Ask<ai.lily.app.remote.HostKeyPrompt>) {
    val p = ask.value
    AlertDialog(
        onDismissRequest = { ask.answer.complete(false) },
        title = { Text("Trust this server?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This is the first time Lily sees this key for ${p.host}:${p.port}. Your password is sent only if you trust it.")
                if (p.knownOtherKeys) {
                    Spacer(Modifier.height(6.dp))
                    Text("You accepted this server before, under a different key type.", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("Key type: ${p.keyType}", style = MaterialTheme.typography.bodySmall)
                Text(p.fingerprint, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                Spacer(Modifier.height(8.dp))
                Text("Compare this with the fingerprint your server shows. If you cannot check it, do not trust it.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { ask.answer.complete(true) }) { Text("Trust") } },
        dismissButton = { TextButton(onClick = { ask.answer.complete(false) }) { Text("Don't trust") } },
    )
}

@Composable
private fun FtpDialog(ask: Ask<ai.lily.app.remote.FtpFallbackPrompt>) {
    AlertDialog(
        onDismissRequest = { ask.answer.complete(false) },
        title = { Text("Use unencrypted FTP?") },
        text = { Text(ask.value.message()) },
        confirmButton = { TextButton(onClick = { ask.answer.complete(true) }) { Text("Use plain FTP") } },
        dismissButton = { TextButton(onClick = { ask.answer.complete(false) }) { Text("Cancel") } },
    )
}

@Composable
private fun ConflictDialog(conflict: ai.lily.app.remote.PushResult.Conflict, ui: RemoteUi) {
    val what = when (conflict.kind) {
        ConflictKind.REMOTE_CHANGED -> "The server's copy of ${conflict.path} changed since you last synced."
        ConflictKind.REMOTE_DELETED -> "${conflict.path} was deleted on the server."
        ConflictKind.REMOTE_APPEARED -> "${conflict.path} already exists on the server and was never synced with this phone."
    }
    AlertDialog(
        onDismissRequest = { ui.conflict = null },
        title = { Text("The server has a different version") },
        text = { Text("$what Choose which one to keep. Nothing was sent yet.") },
        confirmButton = {
            Row {
                TextButton(onClick = { ui.keepMine(conflict.path) }) { Text("Keep mine") }
                TextButton(onClick = { ui.takeServers(conflict.path) }) { Text("Use server's") }
            }
        },
        dismissButton = { TextButton(onClick = { ui.conflict = null }) { Text("Decide later") } },
    )
}

@Composable
private fun BusyDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("Please wait") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(28.dp))
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(text)
            }
        },
    )
}
