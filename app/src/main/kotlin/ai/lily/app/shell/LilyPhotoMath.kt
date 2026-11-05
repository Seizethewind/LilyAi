package ai.lily.app.shell

import kotlin.math.roundToInt

/** The size and file-name rules for Lily's photo. Free of Android, so they are unit tested. */
object LilyPhotoMath {
    /** The longer side of the stored photo, in pixels. Plenty for a circle that is at most about 100 dp wide. */
    const val MAX_SIDE = 512

    private const val PREFIX = "lily-photo-"
    private const val SUFFIX = ".jpg"

    /** The largest power-of-two decode step that still leaves the longer side at [max] or more. 1 means decode at full size. */
    fun sampleSize(width: Int, height: Int, max: Int = MAX_SIDE): Int {
        if (width <= 0 || height <= 0 || max <= 0) return 1
        val longest = maxOf(width, height)
        var sample = 1
        while (longest / (sample * 2) >= max) sample *= 2
        return sample
    }

    /** The size to scale to so the longer side is [max]. A picture that is already that small keeps its size. */
    fun fit(width: Int, height: Int, max: Int = MAX_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return maxOf(width, 1) to maxOf(height, 1)
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val scale = max.toDouble() / longest
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /** A new name for each picked photo, so the picture on screen refreshes and an old file is never overwritten. */
    fun fileName(millis: Long): String = "$PREFIX$millis$SUFFIX"

    /** True for a file this app made for Lily's photo, so clean-up never touches anything else in the folder. */
    fun isOwn(fileName: String): Boolean = fileName.startsWith(PREFIX) && fileName.endsWith(SUFFIX)
}
