package ai.lily.app.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LilyPhotoMathTest {
    @Test
    fun `a big picture is decoded in steps that keep the longer side at 512 or more`() {
        assertEquals(4, LilyPhotoMath.sampleSize(4000, 3000))
        assertEquals(2, LilyPhotoMath.sampleSize(1024, 1024))
        assertEquals(1, LilyPhotoMath.sampleSize(1000, 800))
        assertEquals(1, LilyPhotoMath.sampleSize(512, 512))
        assertEquals(1, LilyPhotoMath.sampleSize(200, 100))
    }

    @Test
    fun `a picture with no size is decoded whole`() {
        assertEquals(1, LilyPhotoMath.sampleSize(0, 0))
        assertEquals(1, LilyPhotoMath.sampleSize(-5, 300))
    }

    @Test
    fun `the longer side is scaled to 512 and the shape is kept`() {
        assertEquals(512 to 256, LilyPhotoMath.fit(1000, 500))
        assertEquals(384 to 512, LilyPhotoMath.fit(3000, 4000))
        assertEquals(512 to 512, LilyPhotoMath.fit(1000, 1000))
    }

    @Test
    fun `a small picture is not enlarged and a thin one never reaches zero`() {
        assertEquals(500 to 300, LilyPhotoMath.fit(500, 300))
        assertEquals(512 to 512, LilyPhotoMath.fit(512, 512))
        assertEquals(512 to 1, LilyPhotoMath.fit(1000, 1))
    }

    @Test
    fun `only photo files made here count as Lily's own`() {
        assertEquals("lily-photo-1730800000000.jpg", LilyPhotoMath.fileName(1730800000000))
        assertTrue(LilyPhotoMath.isOwn(LilyPhotoMath.fileName(5)))
        assertFalse(LilyPhotoMath.isOwn("lily_settings.xml"))
        assertFalse(LilyPhotoMath.isOwn("lily-photo-5.png"))
        assertFalse(LilyPhotoMath.isOwn("worker-photo-5.jpg"))
    }
}
