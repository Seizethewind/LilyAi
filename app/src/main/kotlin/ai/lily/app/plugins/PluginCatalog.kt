package ai.lily.app.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** One plugin offered by a catalog. The manifest it points to is checked again, in full, before anything is installed. */
data class CatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val manifestUrl: String,
)

/**
 * A catalog is a JSON file: `{"schema":1,"plugins":[{"id","name","version","description","manifest"}]}` where `manifest` is an https
 * address. Installing from it only downloads that manifest as text and gives it to the normal installer, so a plugin from the
 * catalog arrives off with nothing allowed, exactly like one pasted in by hand. No plugin code is ever downloaded or run.
 */
object PluginCatalog {
    const val DEFAULT_URL = "https://raw.githubusercontent.com/Seizethewind/LilyAi/main/plugins/index.json"
    private const val MAX_INDEX_BYTES = 256 * 1024
    private const val MAX_ENTRIES = 200
    private val VERSION = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}$")

    /** Entries that are not valid are left out. Throws IllegalArgumentException when the file itself is no catalog. */
    fun parse(text: String): List<CatalogEntry> {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw IllegalArgumentException("the catalog is not valid JSON")
        }
        require(root.opt("schema") == 1) { "the catalog schema must be 1" }
        val list = root.optJSONArray("plugins") ?: throw IllegalArgumentException("the catalog has no plugin list")
        val out = ArrayList<CatalogEntry>()
        for (i in 0 until minOf(list.length(), MAX_ENTRIES)) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.opt("id") as? String ?: continue
            val name = o.opt("name") as? String ?: continue
            val version = o.opt("version") as? String ?: continue
            val description = o.opt("description") as? String ?: ""
            val url = o.opt("manifest") as? String ?: continue
            val ok = ManifestParser.isValidId(id) &&
                name.length in 1..60 && !Visible.hasHidden(name) &&
                VERSION.matches(version) &&
                description.length <= 300 && !Visible.hasHidden(description) &&
                url.startsWith("https://") && url.length <= 500 && url.none { it.isWhitespace() || Visible.isHidden(it) }
            if (ok && out.none { it.id == id }) out += CatalogEntry(id, name, version, description, url)
        }
        return out
    }

    suspend fun loadIndex(url: String): List<CatalogEntry> = parse(fetchText(url, MAX_INDEX_BYTES))

    suspend fun fetchManifest(url: String): String = fetchText(url, ManifestParser.MAX_BYTES)

    /** Plain https GET with a size cap. Anything else is an error, never a silent fallback. */
    private suspend fun fetchText(url: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "only https addresses are allowed" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            check(c.responseCode == 200) { "the server answered ${c.responseCode}" }
            c.inputStream.use { s ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    check(out.size() <= maxBytes) { "the file is larger than ${maxBytes / 1024} KB" }
                }
                out.toString("UTF-8")
            }
        } finally {
            c.disconnect()
        }
    }
}

/** A prompt command's text with {selection}, {file} and {language} filled in, in one pass: a value that looks like a placeholder stays literal. */
fun fillPrompt(template: String, selection: String, file: String, language: String): String =
    ManifestParser.PLACEHOLDER.replace(template) { m ->
        when (m.groupValues[1]) {
            "selection" -> selection
            "file" -> file
            "language" -> language
            else -> m.value
        }
    }
