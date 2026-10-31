package ai.lily.app.lib.editor

/** How tall each line of code is, as a multiple of the font size. */
enum class LineSpacing(val label: String, val factor: Float) {
    COMPACT("Compact", 1.35f),
    NORMAL("Normal", 1.55f),
    RELAXED("Relaxed", 1.8f),
    ;

    companion object {
        fun fromName(name: String?): LineSpacing = values().firstOrNull { it.name == name } ?: NORMAL
    }
}

/** The tab choices offered in settings: match the file, 2, 4 or 8 spaces, or a real tab character. */
val TAB_SIZE_CHOICES = listOf(0, 2, 4, 8, -1)

fun tabSizeLabel(size: Int): String = when {
    size == 0 -> "Match file"
    size < 0 -> "Tab"
    else -> "$size spaces"
}

/**
 * Everything the user can change about how the editor looks and behaves. Font size is kept separately.
 * [theme] is the id of an editor theme. [tabSize] is 0 to follow each file's own indent, -1 for tab characters,
 * or the number of spaces. [autoSave] saves files a few seconds after you stop typing.
 */
data class EditorSettings(
    val theme: String = "dracula",
    val spacing: LineSpacing = LineSpacing.NORMAL,
    val tabSize: Int = 0,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val autoPair: Boolean = true,
    val autoSave: Boolean = false,
) {
    /** The text one Tab or one level of auto-indent inserts. [detected] is what the file itself uses. */
    fun indentFor(detected: String): String = when {
        tabSize == 0 -> detected
        tabSize < 0 -> "\t"
        else -> " ".repeat(tabSize)
    }
}
