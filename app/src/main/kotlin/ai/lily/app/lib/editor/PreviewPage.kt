package ai.lily.app.lib.editor

/** The made-up web address the preview pretends to live at. Requests to it are answered from the workspace files. */
const val PREVIEW_HOST = "lily.preview"

/** A page ready for the preview WebView. [mime] is how it should be read (HTML, or SVG for .svg files). */
data class PreviewDocument(val html: String, val mime: String)

private val PREVIEWABLE = setOf("html", "htm", "xhtml", "svg", "md", "markdown", "js", "mjs")

/** True for files the preview can show: web pages, SVG images, Markdown and JavaScript. */
fun canPreview(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in PREVIEWABLE

/** The content type a file is served with when a page asks for it. */
fun previewMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
    "html", "htm", "xhtml" -> "text/html"
    "css" -> "text/css"
    "js", "mjs" -> "text/javascript"
    "json", "map" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "ico" -> "image/x-icon"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "xml" -> "text/xml"
    "wasm" -> "application/wasm"
    "txt", "md", "markdown", "csv", "log" -> "text/plain"
    else -> "application/octet-stream"
}

/** True for content types that are text, so they are sent as UTF-8. */
fun isTextMime(mime: String): Boolean =
    mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml" || mime == "text/javascript"

// Runs before the page's own scripts. It turns whatever console.log is given (objects, arrays, errors) into readable text,
// because the WebView would otherwise show an object as [object Object]. The marker tells the app the line came from here.
// It is kept on one line so it never changes the line numbers of the page it is added to.
private const val PRELUDE_JS =
    "(function(){var M='\\u200B';function fmt(v){if(typeof v==='string')return v;" +
        "if(v instanceof Error)return v.stack||String(v);" +
        "if(typeof v==='function'||typeof v==='symbol')return String(v);" +
        "if(v===undefined)return 'undefined';" +
        "try{var seen=[];var s=JSON.stringify(v,function(k,x){if(typeof x==='object'&&x!==null){" +
        "if(seen.indexOf(x)>=0)return '[Circular]';seen.push(x);}" +
        "if(typeof x==='function')return '[Function]';return x;},2);return s===undefined?String(v):s;}" +
        "catch(e){return String(v);}}" +
        "['log','info','warn','error','debug'].forEach(function(k){var o=console[k];" +
        "console[k]=function(){var p=[];for(var i=0;i<arguments.length;i++)p.push(fmt(arguments[i]));" +
        "o.call(console,M+p.join(' '));};});})();"

/** The character the prelude puts in front of every console line it handles. */
const val CONSOLE_MARK = "\u200B"

private const val PRELUDE_TAG_OPEN = "<script>"
private const val PRELUDE_TAG_CLOSE = "</script>"

private val HEAD_OPEN = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_OPEN = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)
private val DOCTYPE = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)

/**
 * Puts the console prelude into an HTML page without adding a line break, so error line numbers still match the file.
 * It goes inside <head>, or after <html>, or after the doctype, or at the very start of a fragment. It is never put
 * before a doctype, because that would switch the page into quirks mode.
 */
fun injectPrelude(html: String): String {
    val tag = PRELUDE_TAG_OPEN + PRELUDE_JS + PRELUDE_TAG_CLOSE
    val at = HEAD_OPEN.find(html) ?: HTML_OPEN.find(html) ?: DOCTYPE.find(html)
    if (at == null) return tag + html
    val cut = at.range.last + 1
    return html.substring(0, cut) + tag + html.substring(cut)
}

private const val MARKDOWN_CSS =
    "body{font-family:-apple-system,Roboto,sans-serif;line-height:1.6;max-width:760px;margin:0 auto;padding:16px;" +
        "color:#1c1b1f;word-wrap:break-word}pre{background:#f4f4f6;padding:12px;border-radius:8px;overflow:auto}" +
        "code{background:#f4f4f6;padding:2px 5px;border-radius:4px;font-family:monospace;font-size:.92em}" +
        "pre code{background:none;padding:0}blockquote{margin:0;padding:0 14px;border-left:4px solid #d0bcff;color:#555}" +
        "table{border-collapse:collapse}th,td{border:1px solid #ddd;padding:6px 10px}img{max-width:100%}" +
        "h1,h2{border-bottom:1px solid #eee;padding-bottom:.3em}a{color:#6750a4}hr{border:0;border-top:1px solid #ddd}"

private fun markdownPage(title: String, body: String): String =
    "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<title>${title.replace("&", "&amp;").replace("<", "&lt;")}</title><style>$MARKDOWN_CSS</style></head>" +
        "<body>$body</body></html>"

/**
 * Turns the text of an open file into the page the preview shows.
 * HTML is shown as written (with the console prelude added). SVG is shown as an image. Markdown is rendered.
 * JavaScript runs inside an empty page, and its first line is the first line of the page's script, so errors point at the right line.
 */
fun buildPreviewDocument(path: String, text: String): PreviewDocument =
    when (path.substringAfterLast('.', "").lowercase()) {
        "svg" -> PreviewDocument(text, "image/svg+xml")
        "md", "markdown" -> PreviewDocument(
            markdownPage(path.substringAfterLast('/'), markdownToPreviewHtml(text)),
            "text/html",
        )
        "js", "mjs" -> PreviewDocument(
            "<!doctype html><html><head>" + PRELUDE_TAG_OPEN + PRELUDE_JS + PRELUDE_TAG_CLOSE +
                "</head><body><script>" + text.replace("</script", "<\\/script", ignoreCase = true) +
                "\n</script></body></html>",
            "text/html",
        )
        else -> PreviewDocument(injectPrelude(text), "text/html")
    }
