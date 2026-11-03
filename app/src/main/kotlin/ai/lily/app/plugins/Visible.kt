package ai.lily.app.plugins

/** Helpers so that what the person is shown is what really runs: no hidden or look-alike control characters. */
object Visible {
    fun isHidden(c: Char): Boolean =
        c.isISOControl() ||
            c in '\u200B'..'\u200F' ||
            c in '\u202A'..'\u202E' ||
            c in '\u2066'..'\u2069' ||
            c == '\uFEFF'

    /** True when [text] has a control, zero-width or text-direction character (newline and tab count). */
    fun hasHidden(text: String): Boolean = text.any { isHidden(it) }

    /** [text] with every hidden character except newline and tab replaced by a visible \uXXXX marker. */
    fun escape(text: String): String = buildString {
        for (c in text) {
            if (c == '\n' || c == '\t' || !isHidden(c)) {
                append(c)
            } else {
                append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
    }
}
