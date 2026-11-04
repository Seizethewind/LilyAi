package ai.lily.app.editor

import ai.lily.app.plugins.CatalogEntry
import ai.lily.app.plugins.InstallResult
import ai.lily.app.plugins.ManifestParser
import ai.lily.app.plugins.ParseResult
import ai.lily.app.plugins.PluginCatalog
import ai.lily.app.plugins.PluginServices
import ai.lily.app.ui.Banner
import ai.lily.app.ui.ButtonKind
import ai.lily.app.ui.Hairline
import ai.lily.app.ui.LilyBloom
import ai.lily.app.ui.LilyButton
import ai.lily.app.ui.LilyTextField
import ai.lily.app.ui.LilyUi
import ai.lily.app.ui.Tone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Browse a plugin catalog and install from it with one tap. A plugin installs off, with nothing allowed. Shown inside a sheet. */
@Composable
fun CatalogPanel(services: PluginServices, onNote: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(PluginCatalog.DEFAULT_URL) }
    var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val installed = remember(services.version) { services.registry.list().map { it.manifest.id }.toSet() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LilyTextField(url, { url = it }, "Catalog address", mono = true, keyboardType = KeyboardType.Uri)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LilyButton(
                if (busy) "Loading" else "Load catalog",
                enabled = !busy,
                kind = ButtonKind.Tonal,
                onClick = {
                    scope.launch {
                        busy = true
                        problem = null
                        result = null
                        try {
                            entries = PluginCatalog.loadIndex(url.trim())
                            if (entries.isEmpty()) problem = "The catalog lists nothing."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problem = "Could not load the catalog: ${e.message ?: e::class.java.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            if (busy) LilyBloom(size = 24.dp)
        }
        problem?.let { Banner(it, Tone.Coral) }
        result?.let { Banner(it, Tone.Petal) }
        for (entry in entries) {
            val have = entry.id in installed
            Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("${entry.name} ${entry.version}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(entry.description, style = MaterialTheme.typography.bodySmall, color = LilyUi.colors.textDim)
                }
                LilyButton(
                    if (have) "Installed" else "Install",
                    enabled = !have && !busy,
                    kind = if (have) ButtonKind.Quiet else ButtonKind.Primary,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val message = install(services, entry)
                                result = message
                                onNote(message)
                            } finally {
                                busy = false
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Downloads the entry's manifest, checks it is the plugin the catalog promised, and hands it to the normal installer. Returns what to tell the person. */
private suspend fun install(services: PluginServices, entry: CatalogEntry): String {
    val text = try {
        PluginCatalog.fetchManifest(entry.manifestUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return "Could not download ${entry.name}: ${e.message ?: e::class.java.simpleName}"
    }
    return when (val parsed = ManifestParser.parse(text)) {
        is ParseResult.Invalid -> "${entry.name} was refused: ${parsed.errors.first()}"
        is ParseResult.Ok -> when {
            parsed.manifest.id != entry.id -> "${entry.name} was refused: the manifest is for a different plugin (${parsed.manifest.id})."
            else -> when (val result = services.install(text)) {
                is InstallResult.Rejected -> "${entry.name} was refused: ${result.errors.first()}"
                is InstallResult.Installed -> "${entry.name} installed. It is off and nothing is allowed yet: turn it on in the list."
            }
        }
    }
}
