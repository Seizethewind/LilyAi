package ai.lily.app.terminal.env

/**
 * Finds Alpine's current "mini root file system" from the `latest-releases.yaml` file Alpine publishes in each
 * release folder, so nobody has to type an address, a checksum and a size by hand.
 *
 * The file is a list of entries, each with lines such as:
 * ```
 * -
 *   flavor: alpine-minirootfs
 *   iso: alpine-minirootfs-3.21.3-aarch64.tar.gz
 *   size: 3837340
 *   sha256: <64 hex>
 * ```
 * This reads only those four fields, line by line, and ignores everything else, so extra fields do not matter. It was
 * written from memory of that format and has not been run against the real file: if the entry is not found,
 * [EnvException] carries the start of the text so the real layout can be seen.
 *
 * Trust: the checksum comes from the same https server as the archive. That catches a damaged download, not a hacked
 * server. A release you ship yourself (a `terminal-env.json` on your own GitHub releases) is the stronger setup.
 */
object AlpineReleases {
    const val BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases"

    fun yamlUrl(arch: EnvArch) = "$BASE/${arch.distro}/latest-releases.yaml"

    fun release(yaml: String, arch: EnvArch): EnvRelease {
        val entry = entries(yaml).firstOrNull { it["flavor"] == "alpine-minirootfs" }
            ?: throw EnvException(
                EnvFailure.BadManifest,
                "No alpine-minirootfs entry found. The file starts with:\n" + yaml.take(600),
            )
        val file = entry["iso"] ?: entry["file"]
        val sha = entry["sha256"]?.lowercase()
        val size = entry["size"]?.toLongOrNull()
        if (file == null || sha == null || size == null) {
            throw EnvException(EnvFailure.BadManifest, "The alpine-minirootfs entry lacks a file name, sha256 or size: $entry")
        }
        if (!Regex("[A-Za-z0-9._-]+\\.tar\\.gz").matches(file)) {
            throw EnvException(EnvFailure.BadManifest, "Unexpected archive name \"$file\".")
        }
        val version = Regex("alpine-minirootfs-([0-9A-Za-z.]+)-").find(file)?.groupValues?.get(1) ?: "latest"
        // Go through the strict parser so every limit applies (https, 64 hex, size range, safe version text).
        val manifest = org.json.JSONObject()
            .put("schema", 1)
            .put("version", version)
            .put(
                "assets",
                org.json.JSONObject().put(
                    arch.distro,
                    org.json.JSONObject().put("url", "$BASE/${arch.distro}/$file").put("sha256", sha).put("size", size),
                ),
            )
        return EnvReleaseParser.parse(manifest.toString())
    }

    /** Splits the list into entries of `key: value` pairs. Quotes around values are removed. */
    internal fun entries(yaml: String): List<Map<String, String>> {
        val out = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (raw in yaml.lineSequence()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#") || line == "---") continue
            if (line.trimStart().startsWith("-") && line.trimStart().removePrefix("-").isBlank()) {
                current = mutableMapOf<String, String>().also { out += it }
                continue
            }
            val body = line.trim().removePrefix("- ").trim()
            if (line.trimStart().startsWith("- ")) current = mutableMapOf<String, String>().also { out += it }
            val colon = body.indexOf(':')
            if (colon <= 0) continue
            val key = body.substring(0, colon).trim()
            val value = body.substring(colon + 1).trim().trim('"', '\'')
            current?.put(key, value)
        }
        return out
    }
}
