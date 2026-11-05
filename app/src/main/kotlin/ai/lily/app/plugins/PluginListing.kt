package ai.lily.app.plugins

/**
 * One card on the Plugins screen. Where it comes from decides how it installs: [bundledText] is a manifest that ships in
 * the app, [manifestUrl] is a catalog address, and neither means the person pasted it in.
 */
data class Listing(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val icon: String,
    val accent: String,
    val bundledText: String?,
    val manifestUrl: String?,
    val installed: Boolean,
) {
    /** What the tile shows: the icon, or the first letter of the name. */
    val tile: String
        get() = icon.ifEmpty { if (name.isEmpty()) "" else String(Character.toChars(name.codePointAt(0))).uppercase() }

    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || listOf(name, description, author, id).any { it.contains(q, ignoreCase = true) }
    }
}

/**
 * Everything the All tab shows, once each, in this order: plugins that ship in the app, then the catalog's, then any the
 * person added by hand. The first source to name an id decides how the card installs.
 */
fun mergeListings(
    installed: List<InstalledPlugin>,
    bundled: List<Pair<PluginManifest, String>>,
    catalog: List<CatalogEntry>,
): List<Listing> {
    val have = installed.map { it.manifest.id }.toSet()
    val out = LinkedHashMap<String, Listing>()
    for ((m, text) in bundled) {
        out.putIfAbsent(m.id, Listing(m.id, m.name, m.version, m.description, m.author, m.icon, m.accent, text, null, m.id in have))
    }
    for (e in catalog) {
        out.putIfAbsent(e.id, Listing(e.id, e.name, e.version, e.description, e.author, e.icon, e.accent, null, e.manifestUrl, e.id in have))
    }
    for (p in installed) {
        val m = p.manifest
        out.putIfAbsent(m.id, Listing(m.id, m.name, m.version, m.description, m.author, m.icon, m.accent, null, null, true))
    }
    return out.values.toList()
}
