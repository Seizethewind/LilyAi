package ai.lily.app.remote

import org.json.JSONException
import org.json.JSONObject

/** What a file looked like on the server, and its content, at the last time the phone and the server agreed. */
data class Baseline(val size: Long, val modified: Long, val hash: String)

/** The saved list of baselines. A damaged file reads as empty, which only makes the next pull more careful. */
internal object ManifestCodec {
    private const val VERSION = 1

    fun encode(files: Map<String, Baseline>): String {
        val entries = JSONObject()
        for ((path, b) in files.toSortedMap()) {
            entries.put(path, JSONObject().put("s", b.size).put("m", b.modified).put("h", b.hash))
        }
        return JSONObject().put("version", VERSION).put("files", entries).toString()
    }

    fun decode(text: String): MutableMap<String, Baseline> {
        val out = linkedMapOf<String, Baseline>()
        if (text.isBlank()) return out
        try {
            val entries = JSONObject(text).optJSONObject("files") ?: return out
            for (key in entries.keys()) {
                val o = entries.optJSONObject(key) ?: continue
                val hash = o.optString("h", "")
                if (hash.isEmpty() || !RemotePaths.isSafePath(key)) continue
                out[key] = Baseline(o.optLong("s", -1L), o.optLong("m", -1L), hash)
            }
        } catch (e: JSONException) {
            return linkedMapOf()
        }
        return out
    }
}
