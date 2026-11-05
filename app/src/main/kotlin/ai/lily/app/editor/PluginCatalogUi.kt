package ai.lily.app.editor

import ai.lily.app.plugins.InstallResult
import ai.lily.app.plugins.ManifestParser
import ai.lily.app.plugins.ParseResult
import ai.lily.app.plugins.PluginCatalog
import ai.lily.app.plugins.PluginServices
import kotlinx.coroutines.CancellationException

/** What to tell the person after a plugin was installed or refused. */
internal fun installMessage(name: String, result: InstallResult): String = when (result) {
    is InstallResult.Rejected -> "$name was refused: ${result.errors.first()}"
    is InstallResult.Installed -> "$name installed. It is off and nothing is allowed yet: open it to turn it on."
}

/** Downloads a catalog plugin's manifest, checks it is the plugin the catalog promised, and hands it to the normal installer. Returns what to tell the person. */
internal suspend fun installFromCatalog(services: PluginServices, id: String, name: String, url: String): String {
    val text = try {
        PluginCatalog.fetchManifest(url)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return "Could not download $name: ${e.message ?: e::class.java.simpleName}"
    }
    val parsed = ManifestParser.parse(text)
    if (parsed is ParseResult.Ok && parsed.manifest.id != id) {
        return "$name was refused: the manifest is for a different plugin (${parsed.manifest.id})."
    }
    return installMessage(name, services.install(text))
}
