package ai.lily.app.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

/**
 * Vectors as text. Turso's wire format in this app has no blob type, so a vector is stored as the base64 of its numbers written as
 * 32-bit floats, little-endian: 4 bytes per number, about 5.3 characters per number (a 1536-number vector is 8192 characters).
 */
object VectorCodec {
    fun encode(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return Base64.getEncoder().encodeToString(buf.array())
    }

    /** The vector, or null if the text is not base64 or does not hold exactly [dims] numbers. */
    fun decode(text: String, dims: Int): FloatArray? {
        val v = decodeAny(text) ?: return null
        return if (v.size == dims) v else null
    }

    /** Like [decode] but takes whatever size the text holds. Null if it is not base64, is empty, or is not a whole number of floats. */
    fun decodeAny(text: String): FloatArray? {
        val bytes = try {
            Base64.getDecoder().decode(text.trim())
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty() || bytes.size % 4 != 0) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buf.getFloat() }
    }

    fun norm(v: FloatArray): Double {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x.toDouble()
        return sqrt(sum)
    }

    /** The vector scaled to length 1, or null if it is empty, all zeros, or holds a number that is not finite. */
    fun normalise(v: FloatArray): FloatArray? {
        if (v.isEmpty() || v.any { it.isNaN() || it.isInfinite() }) return null
        val n = norm(v)
        if (!n.isFinite() || n <= 0.0) return null
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    fun dot(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "vectors of different sizes" }
        var sum = 0.0
        for (i in a.indices) sum += a[i].toDouble() * b[i].toDouble()
        return sum
    }
}
