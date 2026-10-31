package ai.lily.app.editor

import ai.lily.app.lib.editor.TokenKind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** The colours of the code area: its background, the line numbers, the cursor and each kind of code. */
class EditorTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val gutterBackground: Color,
    val gutterForeground: Color,
    val cursor: Color,
    tokens: Map<TokenKind, Color>,
) {
    private val styles: Map<TokenKind, SpanStyle> = TokenKind.values().associateWith { kind ->
        val color = tokens[kind] ?: foreground
        when (kind) {
            TokenKind.COMMENT -> SpanStyle(color = color, fontStyle = FontStyle.Italic)
            TokenKind.HEADING -> SpanStyle(color = color, fontWeight = FontWeight.Bold)
            else -> SpanStyle(color = color)
        }
    }

    fun style(kind: TokenKind): SpanStyle = styles[kind] ?: SpanStyle(color = foreground)
}

private fun tokens(
    keyword: Long,
    type: Long,
    string: Long,
    comment: Long,
    number: Long,
    literal: Long,
    tag: Long,
    attribute: Long,
    property: Long,
    heading: Long,
): Map<TokenKind, Color> = mapOf(
    TokenKind.KEYWORD to Color(keyword),
    TokenKind.TYPE to Color(type),
    TokenKind.STRING to Color(string),
    TokenKind.COMMENT to Color(comment),
    TokenKind.NUMBER to Color(number),
    TokenKind.LITERAL to Color(literal),
    TokenKind.TAG to Color(tag),
    TokenKind.ATTRIBUTE to Color(attribute),
    TokenKind.PROPERTY to Color(property),
    TokenKind.HEADING to Color(heading),
)

val EDITOR_THEMES: List<EditorTheme> = listOf(
    EditorTheme(
        id = "dracula",
        name = "Dracula",
        dark = true,
        background = Color(0xFF282A36),
        foreground = Color(0xFFF8F8F2),
        gutterBackground = Color(0xFF21222C),
        gutterForeground = Color(0xFF6272A4),
        cursor = Color(0xFFBD93F9),
        tokens = tokens(
            keyword = 0xFFFF79C6, type = 0xFF8BE9FD, string = 0xFFF1FA8C, comment = 0xFF6272A4, number = 0xFFBD93F9,
            literal = 0xFFBD93F9, tag = 0xFFFF79C6, attribute = 0xFF50FA7B, property = 0xFF50FA7B, heading = 0xFFFFB86C,
        ),
    ),
    EditorTheme(
        id = "light",
        name = "Light",
        dark = false,
        background = Color(0xFFFFFFFF),
        foreground = Color(0xFF1C1B1F),
        gutterBackground = Color(0xFFF3F0F7),
        gutterForeground = Color(0xFF79747E),
        cursor = Color(0xFF6750A4),
        tokens = tokens(
            keyword = 0xFF8E24AA, type = 0xFF00796B, string = 0xFF2E7D32, comment = 0xFF8A8A8A, number = 0xFFD84315,
            literal = 0xFFAD1457, tag = 0xFF1565C0, attribute = 0xFFEF6C00, property = 0xFF0277BD, heading = 0xFF6A1B9A,
        ),
    ),
    EditorTheme(
        id = "dark",
        name = "Dark",
        dark = true,
        background = Color(0xFF1E1E1E),
        foreground = Color(0xFFD4D4D4),
        gutterBackground = Color(0xFF252526),
        gutterForeground = Color(0xFF858585),
        cursor = Color(0xFFAEAFAD),
        tokens = tokens(
            keyword = 0xFFC586C0, type = 0xFF4EC9B0, string = 0xFFCE9178, comment = 0xFF6A9955, number = 0xFFB5CEA8,
            literal = 0xFF569CD6, tag = 0xFF569CD6, attribute = 0xFF9CDCFE, property = 0xFF9CDCFE, heading = 0xFF569CD6,
        ),
    ),
    EditorTheme(
        id = "monokai",
        name = "Monokai",
        dark = true,
        background = Color(0xFF272822),
        foreground = Color(0xFFF8F8F2),
        gutterBackground = Color(0xFF1E1F1C),
        gutterForeground = Color(0xFF75715E),
        cursor = Color(0xFFF8F8F0),
        tokens = tokens(
            keyword = 0xFFF92672, type = 0xFF66D9EF, string = 0xFFE6DB74, comment = 0xFF75715E, number = 0xFFAE81FF,
            literal = 0xFFAE81FF, tag = 0xFFF92672, attribute = 0xFFA6E22E, property = 0xFF66D9EF, heading = 0xFFA6E22E,
        ),
    ),
    EditorTheme(
        id = "solarized-light",
        name = "Solarized Light",
        dark = false,
        background = Color(0xFFFDF6E3),
        foreground = Color(0xFF586E75),
        gutterBackground = Color(0xFFEEE8D5),
        gutterForeground = Color(0xFF93A1A1),
        cursor = Color(0xFF586E75),
        tokens = tokens(
            keyword = 0xFF859900, type = 0xFFB58900, string = 0xFF2AA198, comment = 0xFF93A1A1, number = 0xFFD33682,
            literal = 0xFFCB4B16, tag = 0xFF268BD2, attribute = 0xFFB58900, property = 0xFF268BD2, heading = 0xFFCB4B16,
        ),
    ),
    EditorTheme(
        id = "solarized-dark",
        name = "Solarized Dark",
        dark = true,
        background = Color(0xFF002B36),
        foreground = Color(0xFF93A1A1),
        gutterBackground = Color(0xFF073642),
        gutterForeground = Color(0xFF586E75),
        cursor = Color(0xFF93A1A1),
        tokens = tokens(
            keyword = 0xFF859900, type = 0xFFB58900, string = 0xFF2AA198, comment = 0xFF586E75, number = 0xFFD33682,
            literal = 0xFFCB4B16, tag = 0xFF268BD2, attribute = 0xFFB58900, property = 0xFF268BD2, heading = 0xFFCB4B16,
        ),
    ),
)

/** The theme with this id, or Dracula if the id is unknown (for example after a theme was removed). */
fun editorThemeById(id: String): EditorTheme = EDITOR_THEMES.firstOrNull { it.id == id } ?: EDITOR_THEMES.first()
