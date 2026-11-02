package ai.lily.app.lib.editor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The fonts that come with the phone. They need no file. */
enum class BuiltinFont(val id: String, val label: String) {
    MONOSPACE("monospace", "Monospace"),
    SANS_SERIF("sans-serif", "Sans-serif"),
    SERIF("serif", "Serif"),
}

/**
 * Which font the editor draws code in: one of the phone's own fonts, or a font file the person added. The [id] is what is saved in
 * the settings: `builtin:monospace`, or `file:MyFont.ttf` for a file in the app's fonts folder.
 */
sealed interface FontChoice {
    val id: String

    data class Builtin(val font: BuiltinFont) : FontChoice {
        override val id: String get() = "builtin:${font.id}"
    }

    data class Custom(val fileName: String) : FontChoice {
        override val id: String get() = "file:$fileName"
    }

    companion object {
        const val DEFAULT_ID = "builtin:monospace"
        val DEFAULT: FontChoice = Builtin(BuiltinFont.MONOSPACE)

        /** The choice for a saved id. Anything unknown, unsafe or missing gives [DEFAULT], so a bad setting never breaks the editor. */
        fun parse(id: String?): FontChoice {
            if (id == null) return DEFAULT
            if (id.startsWith("builtin:")) {
                val name = id.removePrefix("builtin:")
                return BuiltinFont.values().firstOrNull { it.id == name }?.let { Builtin(it) } ?: DEFAULT
            }
            if (id.startsWith("file:")) {
                val name = id.removePrefix("file:")
                return if (FontStore.isSafeName(name)) Custom(name) else DEFAULT
            }
            return DEFAULT
        }
    }
}

/** The result of adding a font file. */
sealed interface FontImport {
    data class Imported(val name: String) : FontImport
    data class Rejected(val reason: String) : FontImport
}

/**
 * The font files the person added, kept in one folder owned by the app.
 *
 * A file is accepted only if it has a `.ttf` or `.otf` name, starts with the header of a TrueType or OpenType font, is not
 * bigger than [maxBytes] and the folder holds fewer than [maxFonts] fonts. The name it is saved under is made safe (letters, digits,
 * dot, underscore, space, brackets and hyphen only, no folders) and unique, so nothing the person picks can write outside the folder
 * or replace another font. A rejected file leaves nothing behind.
 */
class FontStore(
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFonts: Int = MAX_FONTS,
) {
    /** The names of the added fonts, in alphabetical order. */
    fun list(): List<String> =
        dir.listFiles()?.filter { it.isFile && isSafeName(it.name) }?.map { it.name }?.sorted() ?: emptyList()

    /** The file for [name], or null if the name is not a safe font name or there is no such file. */
    fun file(name: String): File? {
        if (!isSafeName(name)) return null
        val f = File(dir, name)
        return if (f.isFile) f else null
    }

    /** True if the start of [f] looks like a TrueType or OpenType font. */
    fun looksValid(f: File): Boolean {
        val head = try {
            f.inputStream().use { input ->
                val buf = ByteArray(HEADER_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        } catch (e: IOException) {
            return false
        }
        return hasFontHeader(head)
    }

    fun delete(name: String): Boolean = file(name)?.delete() ?: false

    /** Adds the font in [input], which was picked as [originalName]. The caller closes [input]. */
    fun import(originalName: String, input: InputStream): FontImport {
        val name = sanitize(originalName) ?: return FontImport.Rejected("Choose a font file that ends in .ttf or .otf.")
        if (list().size >= maxFonts) return FontImport.Rejected("There are already $maxFonts fonts. Remove one first.")
        val bytes = try {
            readLimited(input)
        } catch (e: IOException) {
            return FontImport.Rejected("Could not read the file: ${e.message ?: "unknown error"}")
        } ?: return FontImport.Rejected("That file is bigger than ${sizeLabel(maxBytes)}.")
        if (bytes.isEmpty()) return FontImport.Rejected("The file is empty.")
        if (!hasFontHeader(bytes)) return FontImport.Rejected("That does not look like a TrueType or OpenType font.")

        val ext = name.substringAfterLast('.')
        val stem = name.dropLast(ext.length + 1)
        var candidate = name
        var n = 2
        while (File(dir, candidate).exists()) {
            candidate = "$stem-$n.$ext"
            n++
        }
        return try {
            dir.mkdirs()
            File(dir, candidate).writeBytes(bytes)
            FontImport.Imported(candidate)
        } catch (e: IOException) {
            FontImport.Rejected("Could not save the font: ${e.message ?: "unknown error"}")
        }
    }

    /** All the bytes of [input], or null if there are more than [maxBytes]. */
    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sanitize(original: String): String? {
        val raw = original.substringAfterLast('/').substringAfterLast('\\').trim()
        val ext = raw.substringAfterLast('.', "").lowercase()
        if (ext !in EXTENSIONS) return null
        var stem = raw.dropLast(ext.length + 1).map { if (isAllowedChar(it)) it else '_' }.joinToString("")
        stem = stem.replace(Regex("\\.{2,}"), ".").trim('.', ' ').take(MAX_STEM)
        if (stem.isEmpty()) stem = "font"
        return "$stem.$ext"
    }

    private fun sizeLabel(bytes: Long): String = if (bytes >= 1_000_000) "${bytes / 1_000_000} MB" else "$bytes bytes"

    companion object {
        const val MAX_BYTES = 10_000_000L
        const val MAX_FONTS = 20
        private const val MAX_STEM = 60
        private const val MAX_NAME = 100
        private const val HEADER_BYTES = 12
        private val EXTENSIONS = setOf("ttf", "otf")

        private fun isAllowedChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '_' || c == ' ' || c == '(' || c == ')' || c == '-'

        /** True for a plain file name that [FontStore] could have made: no folders, no `..`, only allowed characters, a font extension. */
        fun isSafeName(name: String): Boolean =
            name.length in 1..MAX_NAME && !name.startsWith('.') && !name.contains("..") && name.all { isAllowedChar(it) } &&
                name.substringAfterLast('.', "").lowercase() in EXTENSIONS

        /** True if [bytes] starts like a TrueType (`00 01 00 00` or `true`) or OpenType (`OTTO`) font and is at least a header long. */
        fun hasFontHeader(bytes: ByteArray): Boolean {
            if (bytes.size < HEADER_BYTES) return false
            if (bytes[0] == 0.toByte() && bytes[1] == 1.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()) return true
            val tag = String(bytes, 0, 4, Charsets.ISO_8859_1)
            return tag == "true" || tag == "OTTO"
        }
    }
}
