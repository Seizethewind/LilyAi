package ai.lily.app.tools

import org.json.JSONObject
import org.json.JSONTokener

/** Reads what WebView.evaluateJavascript hands back: a JSON value, so a script that returns a string arrives quoted and escaped. */
object JsResult {
    /** The string a script returned, or null for null, undefined, a missing answer or anything that is not a string. */
    fun text(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
    }

    /** The snapshot script's answer. The text is cut again here, in case the page changed what the script did. */
    fun snapshot(raw: String?, maxText: Int): PageSnapshot? {
        val json = text(raw) ?: return null
        return runCatching {
            val o = JSONObject(json)
            PageSnapshot(o.optString("url"), o.optString("title"), o.optString("text").take(maxText))
        }.getOrNull()
    }
}
