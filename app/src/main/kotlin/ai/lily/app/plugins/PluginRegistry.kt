package ai.lily.app.plugins

sealed interface InstallResult {
    /**
     * [droppedGrants] are permissions that were allowed before and are no longer asked for.
     * [clearedAutoApprove] are tools whose "don't ask again" was removed because the tool changed or lost its permission.
     */
    data class Installed(
        val plugin: InstalledPlugin,
        val updated: Boolean,
        val droppedGrants: Set<String>,
        val clearedAutoApprove: Set<String>,
    ) : InstallResult

    data class Rejected(val errors: List<String>) : InstallResult
}

/**
 * Installing, turning on and allowing plugins. The rules that keep the person in charge:
 * - A new plugin is off and has nothing allowed.
 * - Only permissions the manifest asks for can be allowed.
 * - An update keeps an allowance only if the new manifest still asks for it, and never adds one.
 * - "Don't ask again" for a tool survives an update only if that tool is exactly the same and still allowed.
 * - A web request that is not a plain read (anything but GET) can never be set to "don't ask again".
 */
class PluginRegistry(private val store: PluginStore) {
    fun list(): List<InstalledPlugin> = store.list()

    fun get(id: String): InstalledPlugin? = store.get(id)

    fun install(manifestText: String): InstallResult {
        val manifest = when (val parsed = ManifestParser.parse(manifestText)) {
            is ParseResult.Ok -> parsed.manifest
            is ParseResult.Invalid -> return InstallResult.Rejected(parsed.errors)
        }
        val existing = store.get(manifest.id)
        val carried = if (existing == null) Carried(PluginState(), emptySet(), emptySet()) else carryOver(existing, manifest)
        val plugin = InstalledPlugin(manifest, manifestText, carried.state)
        store.put(plugin)
        return InstallResult.Installed(plugin, existing != null, carried.dropped, carried.cleared)
    }

    fun uninstall(id: String): Boolean {
        if (store.get(id) == null) return false
        store.remove(id)
        return true
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = change(id) { _, s -> s.copy(enabled = enabled) }

    /** Allows one permission the plugin asked for. False if the plugin does not ask for it. */
    fun grant(id: String, permissionKey: String): Boolean = change(id) { p, s ->
        if (p.manifest.permissions.none { it.key == permissionKey }) null else s.copy(granted = s.granted + permissionKey)
    }

    /** Takes a permission back, and with it "don't ask again" for every tool that needs it. */
    fun revoke(id: String, permissionKey: String): Boolean = change(id) { p, s ->
        val stillAuto = s.autoApprove.filter { tool ->
            val key = p.manifest.permissionKeyOf(tool)
            key != null && key != permissionKey
        }.toSet()
        s.copy(granted = s.granted - permissionKey, autoApprove = stillAuto)
    }

    /** "Run this tool without asking each time." Needs the tool to exist, be allowed to skip the question, and its permission to be allowed. */
    fun setAutoApprove(id: String, toolName: String, on: Boolean): Boolean = change(id) { p, s ->
        val key = p.manifest.permissionKeyOf(toolName)
        when {
            key == null -> null
            !on -> s.copy(autoApprove = s.autoApprove - toolName)
            !p.manifest.mayRunWithoutAsking(toolName) -> null
            key !in s.granted -> null
            else -> s.copy(autoApprove = s.autoApprove + toolName)
        }
    }

    private fun change(id: String, edit: (InstalledPlugin, PluginState) -> PluginState?): Boolean {
        val plugin = store.get(id) ?: return false
        val next = edit(plugin, plugin.state) ?: return false
        store.put(plugin.copy(state = next))
        return true
    }

    private class Carried(val state: PluginState, val dropped: Set<String>, val cleared: Set<String>)

    private fun carryOver(old: InstalledPlugin, manifest: PluginManifest): Carried {
        val requested = manifest.permissions.map { it.key }.toSet()
        val granted = old.state.granted.intersect(requested)
        val dropped = old.state.granted - granted
        val kept = LinkedHashSet<String>()
        val cleared = LinkedHashSet<String>()
        for (toolName in old.state.autoApprove) {
            val before = old.manifest.specOf(toolName)
            val after = manifest.specOf(toolName)
            val key = manifest.permissionKeyOf(toolName)
            val stillAllowed = key != null && key in granted && manifest.mayRunWithoutAsking(toolName)
            if (before != null && after != null && before == after && stillAllowed) kept += toolName else cleared += toolName
        }
        return Carried(PluginState(old.state.enabled, granted, kept), dropped, cleared)
    }
}
