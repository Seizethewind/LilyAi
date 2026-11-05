package ai.lily.app.plugins

/** The square tile on a plugin card: [text] on a [color]; [onColor] is the text colour that reads on it. All are ARGB. */
data class PluginLook(val text: String, val color: Long, val onColor: Long)

/**
 * Works out a plugin's tile. The manifest may give an icon and an accent colour; when it does not, the tile shows the
 * first letters of the name on a colour picked from the id, so the same plugin always looks the same and none is blank.
 */
object PluginLooks {
    private val PALETTE = longArrayOf(0xFF3B6EA5, 0xFF6B4FA0, 0xFF2F7D6B, 0xFFB5543A, 0xFF8A6D1F, 0xFF9B3F68)
    private const val DARK_INK = 0xFF111111
    private const val LIGHT_INK = 0xFFFFFFFF

    fun of(id: String, name: String, icon: String, accent: String): PluginLook {
        val text = icon.ifEmpty { initials(name) }
        val color = if (ManifestParser.isValidAccent(accent)) {
            0xFF000000 or accent.substring(1).toLong(16)
        } else {
            PALETTE[Math.floorMod(id.hashCode(), PALETTE.size)]
        }
        return PluginLook(text, color, if (brightness(color) > 0.55) DARK_INK else LIGHT_INK)
    }

    /** One word gives its first two letters, more give the first letter of the first two words. */
    fun initials(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val text = when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2)
            else -> "${words[0][0]}${words[1][0]}"
        }
        return text.uppercase()
    }

    private fun brightness(argb: Long): Double {
        val r = (argb shr 16 and 0xFF) / 255.0
        val g = (argb shr 8 and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
