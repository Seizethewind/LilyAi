package ai.lily.app.editor

import ai.lily.app.plugins.CatalogEntry
import ai.lily.app.plugins.InstallResult
import ai.lily.app.plugins.ManifestParser
import ai.lily.app.plugins.ParseResult
import ai.lily.app.plugins.PluginCatalog
import ai.lily.app.plugins.PluginServices
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Browse a plugin catalog and install from it with one tap. A plugin installs off, with nothing allowed. */
@Composable
fun CatalogPanel(services: PluginServices, onNote: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(PluginCatalog.DEFAULT_URL) }
    var entries by remember { mutableStateOf<List<CatalogEntry>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val installed = remember(services.version) { services.registry.list().map { it.manifest.id }.toSet() }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Catalog", style = MaterialTheme.typography.titleSmall)
        TextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    status = null
                    try {
                        entries = PluginCatalog.loadIndex(url.trim())
                        if (entries.isEmpty()) status = "The catalog lists nothing."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "Could not load the catalog: ${e.message ?: e::class.java.simpleName}"
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text(if (busy) "Working..." else "Load catalog") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        for (entry in entries) {
            val have = entry.id in installed
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${entry.name} ${entry.version}", style = MaterialTheme.typography.bodyMedium)
                    Text(entry.description, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    enabled = !have && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                onNote(install(services, entry))
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (have) "Installed" else "Install") }
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
                is InstallResult.Installed -> "${entry.name} installed. It is off and nothing is allowed yet: turn it on in the list below."
            }
        }
    }
}
