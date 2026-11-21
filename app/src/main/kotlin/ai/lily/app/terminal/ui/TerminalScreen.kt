package ai.lily.app.terminal.ui

import ai.lily.app.terminal.env.EnvState
import ai.lily.app.terminal.env.EnvStep
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyUi
import android.graphics.Typeface
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.view.TerminalView

/** The Terminal section: set the Linux environment up on first use, then a tabbed shell with an extra-keys row. */
@Composable
fun TerminalScreen(host: TerminalHost) {
    val state by host.env.state.collectAsState()
    LaunchedEffect(Unit) { host.refresh() }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().imePadding()) {
        if (state is EnvState.Ready) {
            // Open the first shell on its own, but not again after a failed start (the error would loop).
            LaunchedEffect(host.tabs.size, host.error) {
                if (host.tabs.isEmpty() && host.error == null) host.newTab()
            }
            ShellPane(host)
        } else {
            SetupPane(host, state)
        }
    }
}

@Composable
private fun SetupPane(host: TerminalHost, state: EnvState) {
    val c = LilyUi.colors
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Terminal", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "A Linux shell inside Lily (Alpine Linux, run through proot). It needs a one-time download of the Linux files, " +
                "a few megabytes. After that it works offline; install more tools with apk, for example apk add git python3 nodejs.",
            color = c.textDim,
        )
        when (state) {
            is EnvState.Installing -> {
                val label = when (state.step) {
                    EnvStep.Downloading -> "Downloading"
                    EnvStep.Verifying -> "Checking the download"
                    EnvStep.Unpacking -> "Unpacking (${state.done} files)"
                    EnvStep.Finalizing -> "Finishing"
                }
                Text(label, color = MaterialTheme.colorScheme.onBackground)
                if (state.step == EnvStep.Downloading && state.total > 0) {
                    LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${state.done / 1024} of ${state.total / 1024} KB", color = c.textDim)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = { host.cancelInstall() }) { Text("Cancel") }
            }
            is EnvState.Broken -> {
                Text("The Linux files are damaged: ${state.reason}", color = c.coral)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { host.install() }) { Text("Repair (download again)") }
                    OutlinedButton(onClick = { host.reset() }) { Text("Remove") }
                }
            }
            else -> {
                Button(onClick = { host.install() }, enabled = host.arch != null) {
                    Text(if (state is EnvState.Failed) "Try again" else "Download Linux files")
                }
            }
        }
        if (state is EnvState.Failed) Text(state.message, color = c.coral)
        host.error?.let { Text(it, color = c.coral) }
        val problems = host.problems()
        if (problems.isNotEmpty()) {
            Text("This build cannot start a shell yet:", color = c.coral)
            problems.forEach { Text("• $it", color = c.textDim) }
        }
    }
}

@Composable
private fun ShellPane(host: TerminalHost) {
    var menu by remember { mutableStateOf(false) }
    val c = LilyUi.colors

    Row(
        Modifier.fillMaxWidth().background(c.raised).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            host.tabs.forEachIndexed { i, tab ->
                val on = i == host.selected
                Text(
                    (if (tab.finished) "✕ " else "") + tab.title,
                    color = if (on) MaterialTheme.colorScheme.onBackground else c.textDim,
                    maxLines = 1,
                    modifier = Modifier
                        .widthIn(max = 160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) c.petalSoft else Color.Transparent)
                        .clickable { host.selected = i }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            TextButton(onClick = { host.newTab() }) { Text("+") }
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; host.paste() })
                DropdownMenuItem(text = { Text("Close this tab") }, onClick = { menu = false; host.closeTab(host.selected) })
                DropdownMenuItem(
                    text = { Text(if (host.seccompOff) "Safe mode is on (tap to turn off)" else "Safe mode (slower, if it hangs)") },
                    onClick = { menu = false; host.seccompOff = !host.seccompOff; host.closeAll() },
                )
                DropdownMenuItem(text = { Text("Remove Linux files") }, onClick = { menu = false; host.reset() })
            }
        }
    }
    Hairline()

    host.error?.let { Text(it, color = c.coral, modifier = Modifier.background(c.raised).fillMaxWidth().padding(10.dp)) }

    val density = LocalDensity.current
    val textPx = with(density) { 14.sp.roundToPx() }
    AndroidView(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTerminalViewClient(host.viewClient)
                setTextSize(textPx)
                setTypeface(Typeface.MONOSPACE)
                isFocusable = true
                isFocusableInTouchMode = true
                host.view = this
            }
        },
        update = { v ->
            host.view = v
            val session = host.tabs.getOrNull(host.selected)?.session
            if (session != null && v.currentSession !== session) v.attachSession(session)
            v.onScreenUpdated()
        },
    )

    ExtraKeys(host)
}

@Composable
private fun ExtraKeys(host: TerminalHost) {
    val c = LilyUi.colors
    Hairline()
    Row(
        Modifier.fillMaxWidth().background(c.raised).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key("Esc") { host.sendKey(KeyEvent.KEYCODE_ESCAPE) }
        Key("Tab") { host.sendKey(KeyEvent.KEYCODE_TAB) }
        Key("Ctrl", on = host.ctrl) { host.ctrl = !host.ctrl }
        Key("Alt", on = host.alt) { host.alt = !host.alt }
        Key("←") { host.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        Key("↓") { host.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        Key("↑") { host.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        Key("→") { host.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Key("PgUp") { host.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        Key("PgDn") { host.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        Key("Home") { host.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        Key("End") { host.sendKey(KeyEvent.KEYCODE_MOVE_END) }
        for (symbol in listOf("-", "/", "|", "~", "_", "\\", "\"", "'", "$", "&", ":", ";")) Key(symbol) { host.sendText(symbol) }
        Key("Paste") { host.paste() }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun Key(label: String, on: Boolean = false, onClick: () -> Unit) {
    val c = LilyUi.colors
    Text(
        label,
        color = if (on) c.onStamen else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) c.stamen else c.sunken)
            .clickable(onClick = onClick)
            .widthIn(min = 40.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}
