package ai.lily.app.editor

import ai.lily.app.lib.editor.injectPrelude
import ai.lily.app.lib.editor.isTextMime
import ai.lily.app.lib.editor.previewMime
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

private const val MAX_PREVIEW_BYTES = 15_000_000L

// No caching, so an edited stylesheet or script is never served stale. Same-origin only, so no CORS needed beyond fonts.
private val RESPONSE_HEADERS = mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")

private fun readWorkspaceBytes(root: File, path: String): ByteArray {
    val base = root.canonicalFile
    val f = File(base, path).canonicalFile
    if (!f.path.startsWith(base.path + File.separator)) throw IOException("Outside the workspace")
    if (!f.isFile) throw IOException("Not found")
    if (f.length() > MAX_PREVIEW_BYTES) throw IOException("Too large")
    return f.readBytes()
}

/**
 * Answers a request the preview page made to its own address from the workspace in [root].
 * [rawPath] is the part of the address after the host. [openText] gives the text of a file that is open in a tab, which is
 * used instead of the file on disk, so unsaved typing in a stylesheet or script shows up in the page. A missing file is a 404.
 * HTML pages that are reached by following a link get the console prelude too.
 */
fun servePreviewFile(root: File, rawPath: String, openText: (String) -> String?): WebResourceResponse {
    var path = rawPath.trimStart('/')
    if (path.isEmpty() || path.endsWith("/")) path += "index.html"
    val mime = previewMime(path)
    return try {
        val raw = openText(path)?.toByteArray(Charsets.UTF_8) ?: readWorkspaceBytes(root, path)
        val body = if (mime == "text/html") {
            injectPrelude(String(raw, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        } else {
            raw
        }
        WebResourceResponse(mime, if (isTextMime(mime)) "utf-8" else null, 200, "OK", RESPONSE_HEADERS, ByteArrayInputStream(body))
    } catch (e: IOException) {
        WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            RESPONSE_HEADERS,
            ByteArrayInputStream("Not found: $path".toByteArray(Charsets.UTF_8)),
        )
    }
}
