package ai.lily.app.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VectorCodecTest {
    @Test
    fun aVectorSurvivesBeingStoredAsText() {
        val v = floatArrayOf(0.1f, -2.5f, 3.14159f, 0f, 1e-8f, 123456.78f)

        val back = VectorCodec.decode(VectorCodec.encode(v), v.size)

        assertNotNull(back)
        assertArrayEquals(v, back, 0f)
    }

    @Test
    fun theNumbersAreLittleEndianFloat32() {
        // 1.0f is the bytes 00 00 80 3F.
        assertEquals("AACAPw==", VectorCodec.encode(floatArrayOf(1f)))
    }

    @Test
    fun textOfTheWrongSizeOrNotBase64IsNotAVector() {
        val text = VectorCodec.encode(floatArrayOf(1f, 2f, 3f))

        assertNull(VectorCodec.decode(text, 2))
        assertNull(VectorCodec.decode(text, 4))
        assertNull(VectorCodec.decode("not base64 !!", 3))
        assertNull(VectorCodec.decode("", 0))
        assertNull(VectorCodec.decodeAny("AAAA")) // 3 bytes: not a whole float
        assertEquals(3, VectorCodec.decodeAny(text)!!.size)
    }

    @Test
    fun normalisingGivesLengthOneAndKeepsTheDirection() {
        val n = VectorCodec.normalise(floatArrayOf(3f, 4f))!!

        assertEquals(0.6f, n[0], 1e-6f)
        assertEquals(0.8f, n[1], 1e-6f)
        assertEquals(1.0, VectorCodec.norm(n), 1e-6)
    }

    @Test
    fun emptyZeroAndNonFiniteVectorsCannotBeNormalised() {
        assertNull(VectorCodec.normalise(floatArrayOf()))
        assertNull(VectorCodec.normalise(floatArrayOf(0f, 0f)))
        assertNull(VectorCodec.normalise(floatArrayOf(1f, Float.NaN)))
        assertNull(VectorCodec.normalise(floatArrayOf(Float.POSITIVE_INFINITY, 1f)))
    }

    @Test
    fun dotProductOfUnitVectorsIsTheirSimilarity() {
        val a = VectorCodec.normalise(floatArrayOf(1f, 0f))!!
        val b = VectorCodec.normalise(floatArrayOf(1f, 1f))!!
        val c = VectorCodec.normalise(floatArrayOf(0f, 1f))!!

        assertEquals(1.0, VectorCodec.dot(a, a), 1e-6)
        assertEquals(0.7071, VectorCodec.dot(a, b), 1e-3)
        assertEquals(0.0, VectorCodec.dot(a, c), 1e-6)
    }
}
