package ai.lily.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.TreeMap

/**
 * What the person has decided about one installed plugin.
 * A plugin starts off, with nothing allowed. [granted] holds [Permission.key] values; [autoApprove] holds tool names the
 * person chose to run without asking each time (never set by a manifest).
 */
data class PluginState(
    val enabled: Boolean = false,
    val granted: Set<String> = emptySet(),
    val autoApprove: Set<String> = emptySet(),
)

/** [manifestText] is kept exactly as installed, so it is checked again every time it is loaded. */
data class InstalledPlugin(val manifest: PluginManifest, val manifestText: String, val state: PluginState)

interface PluginStore {
    fun list(): List<InstalledPlugin>
    fun get(id: String): InstalledPlugin?
    fun put(plugin: InstalledPlugin)
    fun remove(id: String)
}

class InMemoryPluginStore : PluginStore {
    private val plugins = TreeMap<String, InstalledPlugin>()
    override fun list(): List<InstalledPlugin> = plugins.values.toList()
    override fun get(id: String): InstalledPlugin? = plugins[id]
    override fun put(plugin: InstalledPlugin) {
        plugins[plugin.manifest.id] = plugin
    }

    override fun remove(id: String) {
        plugins.remove(id)
    }
}

/**
 * One small JSON file per plugin in [dir], written through a temporary file so a crash cannot leave half a file.
 * A file that is damaged, too large, no longer a valid manifest, or named for a different plugin is skipped and
 * listed in [problems]; it never stops the other plugins from loading. Stored permissions are intersected with what
 * the manifest asks for, so a hand-edited file cannot allow more than the plugin requested.
 */
class DiskPluginStore(private val dir: File) : PluginStore {
    /** Files skipped by the most recent [list], with the reason. */
    var problems: List<String> = emptyList()
        private set

    override fun list(): List<InstalledPlugin> {
        val found = ArrayList<InstalledPlugin>()
        val skipped = ArrayList<String>()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.sortedBy { it.name } ?: emptyList()
        for (f in files) {
            val plugin = read(f)
            if (plugin == null) skipped += "${f.name}: damaged or invalid, skipped" else found += plugin
        }
        problems = skipped
        return found
    }

    override fun get(id: String): InstalledPlugin? {
        if (!ManifestParser.isValidId(id)) return null
        val f = File(dir, id + EXT)
        return if (f.isFile) read(f) else null
    }

    override fun put(plugin: InstalledPlugin) {
        dir.mkdirs()
        val json = JSONObject()
            .put("manifest", plugin.manifestText)
            .put("enabled", plugin.state.enabled)
            .put("granted", JSONArray(plugin.state.granted.sorted()))
            .put("autoApprove", JSONArray(plugin.state.autoApprove.sorted()))
        val target = File(dir, plugin.manifest.id + EXT)
        val temp = File(dir, "." + plugin.manifest.id + ".tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun remove(id: String) {
        if (!ManifestParser.isValidId(id)) return
        File(dir, id + EXT).delete()
    }

    private fun read(f: File): InstalledPlugin? {
        try {
            if (f.length() > MAX_FILE_BYTES) return null
            val json = JSONObject(f.readText(Charsets.UTF_8))
            val text = json.getString("manifest")
            val manifest = (ManifestParser.parse(text) as? ParseResult.Ok)?.manifest ?: return null
            if (f.name != manifest.id + EXT) return null
            val requested = manifest.permissions.map { it.key }.toSet()
            val granted = strings(json.optJSONArray("granted")).intersect(requested)
            val auto = strings(json.optJSONArray("autoApprove")).intersect(manifest.toolNames())
            return InstalledPlugin(manifest, text, PluginState(json.optBoolean("enabled", false), granted, auto))
        } catch (e: Exception) {
            return null
        }
    }

    private fun strings(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            (array.opt(i) as? String)?.let { out += it }
        }
        return out
    }

    private companion object {
        const val EXT = ".json"
        const val MAX_FILE_BYTES = 256 * 1024L
    }
}
