package ai.lily.app.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Lily's photo. A picked picture is copied into the app's private folder and scaled down, so it keeps working after the person
 * deletes the original or the picker's temporary access ends, and so the file stays small. The path is not a secret.
 */
object LilyPhoto {
    /**
     * Copies the picture at [uri] into the app's private folder with its longer side at most [LilyPhotoMath.MAX_SIDE] pixels, turned
     * upright by its EXIF note. Returns the new file's path, or null when it could not be read as a picture.
     * This reads and writes files, so call it off the main thread.
     */
    fun import(context: Context, uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val decode = BitmapFactory.Options().apply { inSampleSize = LilyPhotoMath.sampleSize(bounds.outWidth, bounds.outHeight) }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) } ?: return null
            val degrees = resolver.openInputStream(uri)?.use { rotation(it) } ?: 0
            val (w, h) = LilyPhotoMath.fit(decoded.width, decoded.height)
            val scaled = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
            val upright = if (degrees == 0) {
                scaled
            } else {
                Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
            }
            val file = File(context.filesDir, LilyPhotoMath.fileName(System.currentTimeMillis()))
            FileOutputStream(file).use { upright.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            file.absolutePath
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Clockwise degrees the picture must be turned to stand upright. A picture with no note, or one that cannot be read, needs none. */
    private fun rotation(input: InputStream): Int = runCatching {
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** Deletes every photo file this app made for Lily except those in [keep], so a picture that was picked but never saved does not pile up. */
    fun cleanup(context: Context, keep: Set<String>) {
        context.filesDir.listFiles()?.forEach { f ->
            if (LilyPhotoMath.isOwn(f.name) && f.absolutePath !in keep) f.delete()
        }
    }

    /** The picture at [path], or null when there is none or the file is gone, in which case the Lily mark shows instead. */
    fun load(path: String): ImageBitmap? {
        if (path.isBlank()) return null
        return runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
    }
}
