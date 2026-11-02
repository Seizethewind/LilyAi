package ai.lily.app.lib.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

class FontStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun ttf() = byteArrayOf(0, 1, 0, 0) + ByteArray(52)
    private fun otf() = "OTTO".toByteArray(Charsets.ISO_8859_1) + ByteArray(60)
    private fun dir() = File(tmp.root, "fonts")
    private fun store(maxBytes: Long = 1_000, maxFonts: Int = 20) = FontStore(dir(), maxBytes, maxFonts)

    private fun FontStore.add(name: String, bytes: ByteArray = ttf()): FontImport = import(name, bytes.inputStream())

    @Test
    fun aTrueTypeAndAnOpenTypeFontAreAccepted() {
        val s = store()

        assertEquals(FontImport.Imported("Mono.ttf"), s.add("Mono.ttf"))
        assertEquals(FontImport.Imported("Pretty.otf"), s.add("Pretty.otf", otf()))

        assertEquals(listOf("Mono.ttf", "Pretty.otf"), s.list())
        val f = s.file("Mono.ttf")
        assertNotNull(f)
        assertTrue(f!!.readBytes().contentEquals(ttf()))
        assertTrue(s.looksValid(f))
    }

    @Test
    fun aFileWithTheWrongEndingIsRejectedAndNothingIsWritten() {
        val s = store()

        val r = s.add("notes.txt")

        assertTrue(r is FontImport.Rejected)
        assertTrue(s.list().isEmpty())
        assertFalse(dir().exists())
    }

    @Test
    fun aFileThatDoesNotStartLikeAFontIsRejected() {
        val s = store()

        val fake = s.add("fake.ttf", "hello world, this is not a font".toByteArray())
        val tiny = s.add("tiny.ttf", byteArrayOf(0, 1, 0, 0))

        assertTrue((fake as FontImport.Rejected).reason.contains("does not look like"))
        assertTrue(tiny is FontImport.Rejected)
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun anEmptyFileIsRejected() {
        val r = store().add("empty.ttf", ByteArray(0))

        assertEquals("The file is empty.", (r as FontImport.Rejected).reason)
    }

    @Test
    fun aFileOverTheSizeLimitIsRejected() {
        val s = store(maxBytes = 100)

        val tooBig = s.add("big.ttf", byteArrayOf(0, 1, 0, 0) + ByteArray(97))
        val justRight = s.add("ok.ttf", byteArrayOf(0, 1, 0, 0) + ByteArray(96))

        assertTrue((tooBig as FontImport.Rejected).reason.contains("bigger than 100 bytes"))
        assertEquals(FontImport.Imported("ok.ttf"), justRight)
    }

    @Test
    fun folderTricksInTheNameStayInsideTheFontsFolder() {
        val s = store()

        assertEquals(FontImport.Imported("evil.ttf"), s.add("../../evil.ttf"))
        assertEquals(FontImport.Imported("c.ttf"), s.add("a\\b\\c.ttf"))

        assertTrue(File(dir(), "evil.ttf").isFile)
        assertFalse(File(tmp.root, "evil.ttf").exists())
        assertEquals(listOf("c.ttf", "evil.ttf"), s.list())
    }

    @Test
    fun oddCharactersAreReplacedAndTheEndingIsLowerCased() {
        val s = store()

        assertEquals(FontImport.Imported("gr__e _ co_.ttf"), s.add("gr\u00f6\u00dfe & co!.ttf"))
        assertEquals(FontImport.Imported("Big.ttf"), s.add("Big.TTF"))
    }

    @Test
    fun aNameWithNothingBeforeTheEndingBecomesFont() {
        assertEquals(FontImport.Imported("font.ttf"), store().add(".ttf"))
    }

    @Test
    fun theSameNameTwiceKeepsBothFonts() {
        val s = store()

        assertEquals(FontImport.Imported("a.ttf"), s.add("a.ttf"))
        assertEquals(FontImport.Imported("a-2.ttf"), s.add("a.ttf"))
        assertEquals(FontImport.Imported("a-3.ttf"), s.add("a.ttf"))
        assertEquals(listOf("a-2.ttf", "a-3.ttf", "a.ttf"), s.list())
    }

    @Test
    fun thereIsALimitOnHowManyFontsAreKept() {
        val s = store(maxFonts = 2)
        s.add("a.ttf")
        s.add("b.ttf")

        val r = s.add("c.ttf")

        assertTrue((r as FontImport.Rejected).reason.contains("already 2 fonts"))
        assertEquals(listOf("a.ttf", "b.ttf"), s.list())
    }

    @Test
    fun aFontCanBeDeleted() {
        val s = store()
        s.add("a.ttf")

        assertFalse(s.delete("missing.ttf"))
        assertFalse(s.delete("../x.ttf"))
        assertTrue(s.delete("a.ttf"))
        assertNull(s.file("a.ttf"))
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun strayFilesAreNotListedAndUnsafeNamesAreNotFound() {
        val s = store()
        s.add("a.ttf")
        File(dir(), "readme.txt").writeText("x")
        File(dir(), ".hidden.ttf").writeBytes(ttf())

        assertEquals(listOf("a.ttf"), s.list())
        assertNull(s.file("../fonts/a.ttf"))
        assertNull(s.file(".hidden.ttf"))
        assertNull(s.file("nothere.ttf"))
    }

    @Test
    fun aFileThatWasReplacedByNonsenseIsNoLongerValid() {
        val s = store()
        s.add("a.ttf")
        File(dir(), "a.ttf").writeText("this used to be a font, honest")

        assertFalse(s.looksValid(File(dir(), "a.ttf")))
        assertFalse(s.looksValid(File(dir(), "gone.ttf")))
    }

    @Test
    fun aReadErrorIsReportedNotThrown() {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("disk on fire")
        }

        val r = store().import("a.ttf", broken)

        assertTrue((r as FontImport.Rejected).reason.contains("disk on fire"))
    }

    @Test
    fun safeNamesAreRecognised() {
        listOf("a.ttf", "a b (1).otf", "My-Font_v2.TTF").forEach { assertTrue(it, FontStore.isSafeName(it)) }
        listOf("", "../a.ttf", "a/b.ttf", "a\\b.ttf", ".hidden.ttf", "a..b.ttf", "a.txt", "noext", "x".repeat(98) + ".ttf", "gr\u00f6\u00dfe.ttf")
            .forEach { assertFalse(it, FontStore.isSafeName(it)) }
    }
}
