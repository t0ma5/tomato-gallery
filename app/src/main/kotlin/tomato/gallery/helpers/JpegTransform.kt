package tomato.gallery.helpers

import java.io.File

/**
 * Lossless JPEG rotation backed by mozjpeg's transupp (jpegtran).
 * Rotates DCT coefficients without decoding pixels, keeping quality
 * and file size. Markers (EXIF/XMP/ICC) are copied as-is.
 */
object JpegTransform {
    val isAvailable: Boolean = runCatching { System.loadLibrary("jpegoptim-jni") }.isSuccess

    @JvmStatic
    private external fun rotateNative(src: String, dest: String, degrees: Int): Int

    fun rotate(src: String, dest: String, degrees: Int): Boolean {
        val normalized = ((degrees % 360) + 360) % 360
        if (!isAvailable || normalized !in setOf(90, 180, 270)) {
            return false
        }
        return try {
            rotateNative(src, dest, normalized) == 0 && File(dest).length() > 0L
        } catch (_: Throwable) {
            File(dest).delete()
            false
        }
    }
}
