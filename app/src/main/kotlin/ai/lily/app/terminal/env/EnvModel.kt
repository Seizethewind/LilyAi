package ai.lily.app.terminal.env

import org.json.JSONObject

/** Why an environment operation failed. The screen maps each one to a message and a next step. */
enum class EnvFailure { BadManifest, UnsupportedDevice, Network, Checksum, Archive, Disk, Broken }

class EnvException(val failure: EnvFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/** The CPU families the Linux environment is published for. [abi] is Android's name, [distro] the Alpine name. */
enum class EnvArch(val abi: String, val distro: String) {
    ARM64("arm64-v8a", "aarch64"),
    ARM("armeabi-v7a", "armv7"),
    X86_64("x86_64", "x86_64");

    companion object {
        /** First supported entry of Build.SUPPORTED_ABIS (Android lists them best first), or null. */
        fun fromAbis(abis: List<String>): EnvArch? =
            abis.firstNotNullOfOrNull { abi -> values().firstOrNull { it.abi == abi } }
    }
}

/** One downloadable archive: where it is, how big, and its SHA-256 (lowercase hex). */
data class EnvAsset(val url: String, val sha256: String, val sizeBytes: Long)

/** A published environment version with one archive per architecture. */
data class EnvRelease(val version: String, val assets: Map<String, EnvAsset>) {
    fun assetFor(arch: EnvArch): EnvAsset? = assets[arch.distro]
}

/**
 * Reads the release manifest (`terminal-env.json`), for example:
 * `{"schema":1,"version":"2026.11.1","assets":{"aarch64":{"url":"https://...","sha256":"<64 hex>","size":3500000}}}`
 *
 * Strict on purpose: the file decides what gets downloaded and unpacked on the phone, so https only, a full SHA-256,
 * a sane size and a plain version string. Anything else is an error that lists the problem.
 */
object EnvReleaseParser {
    const val MAX_ARCHIVE_BYTES = 1L shl 30
    private val versionRe = Regex("[0-9A-Za-z._-]{1,40}")
    private val shaRe = Regex("[0-9a-f]{64}")

    fun parse(json: String): EnvRelease {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw EnvException(EnvFailure.BadManifest, "The environment manifest is not valid JSON.", e)
        }
        if (root.optInt("schema", -1) != 1) bad("The environment manifest has an unsupported schema.")
        val version = root.optString("version")
        if (!versionRe.matches(version)) bad("The environment version \"$version\" is not allowed.")
        val assetsJson = root.optJSONObject("assets") ?: bad("The environment manifest lists no archives.")
        val assets = mutableMapOf<String, EnvAsset>()
        for (key in assetsJson.keys()) {
            val a = assetsJson.optJSONObject(key) ?: bad("Archive \"$key\" is not an object.")
            val url = a.optString("url")
            if (!url.startsWith("https://")) bad("Archive \"$key\" must be downloaded over https.")
            val sha = a.optString("sha256")
            if (!shaRe.matches(sha)) bad("Archive \"$key\" needs a lowercase 64 character sha256.")
            val size = a.optLong("size", -1)
            if (size <= 0 || size > MAX_ARCHIVE_BYTES) bad("Archive \"$key\" has a size that is not allowed.")
            assets[key] = EnvAsset(url, sha, size)
        }
        if (assets.isEmpty()) bad("The environment manifest lists no archives.")
        return EnvRelease(version, assets)
    }

    private fun bad(message: String): Nothing = throw EnvException(EnvFailure.BadManifest, message)
}

/** What the screen shows. Only one install runs at a time. */
sealed interface EnvState {
    /** Nothing installed yet. */
    data object Missing : EnvState

    /** [step] is what is happening; [done] and [total] are bytes for Downloading and entries for Unpacking (total 0 = unknown). */
    data class Installing(val step: EnvStep, val done: Long, val total: Long) : EnvState

    data class Ready(val version: String, val arch: String) : EnvState

    /** Files are there but something needed is missing or unreadable; Repair fixes it. */
    data class Broken(val reason: String) : EnvState

    /** The last install failed. A partial download is kept so the next try resumes. */
    data class Failed(val failure: EnvFailure, val message: String) : EnvState
}

enum class EnvStep { Downloading, Verifying, Unpacking, Finalizing }
