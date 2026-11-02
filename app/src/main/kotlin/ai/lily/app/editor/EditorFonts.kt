package ai.lily.app.editor

import ai.lily.app.lib.editor.BuiltinFont
import ai.lily.app.lib.editor.FontChoice
import ai.lily.app.lib.editor.FontStore
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/** Turns a saved font choice into something the editor can draw with. */
object EditorFonts {
    /**
     * The family for [choice], or null if it is a font file that is missing, is not a font, or could not be loaded. The caller then
     * falls back to monospace, so a deleted or damaged font file never breaks the editor.
     */
    fun familyFor(choice: FontChoice, store: FontStore): FontFamily? = when (choice) {
        is FontChoice.Builtin -> when (choice.font) {
            BuiltinFont.MONOSPACE -> FontFamily.Monospace
            BuiltinFont.SANS_SERIF -> FontFamily.SansSerif
            BuiltinFont.SERIF -> FontFamily.Serif
        }

        is FontChoice.Custom -> {
            val f = store.file(choice.fileName)
            if (f == null || !store.looksValid(f)) null else runCatching { FontFamily(Typeface.createFromFile(f)) }.getOrNull()
        }
    }
}
