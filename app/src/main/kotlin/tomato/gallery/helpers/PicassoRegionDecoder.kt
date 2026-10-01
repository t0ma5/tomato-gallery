package tomato.gallery.helpers

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
import androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE
import androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE
import androidx.exifinterface.media.ExifInterface.ORIENTATION_UNDEFINED
import com.davemorrissey.labs.subscaleview.ImageRegionDecoder

class PicassoRegionDecoder(
    val showHighestQuality: Boolean,
    val screenWidth: Int,
    val screenHeight: Int,
    val minTileDpi: Int,
    val orientation: Int = ORIENTATION_NORMAL
) : ImageRegionDecoder {
    private var decoder: BitmapRegionDecoder? = null
    private val decoderLock = Any()

    override fun init(context: Context, uri: Uri): Point {
        val newUri = Uri.parse(uri.toString().replace("%", "%25").replace("#", "%23"))
        val inputStream = context.contentResolver.openInputStream(newUri)
        decoder = BitmapRegionDecoder.newInstance(inputStream!!, false)
        val swapped = OrientationTransformation.whSwapped(orientation)
        val w = if (swapped) decoder!!.height else decoder!!.width
        val h = if (swapped) decoder!!.width else decoder!!.height
        return Point(w, h)
    }

    override fun decodeRegion(rect: Rect, sampleSize: Int): Bitmap {
        synchronized(decoderLock) {
            var newSampleSize = sampleSize
            if (!showHighestQuality && minTileDpi == LOW_TILE_DPI) {
                if ((rect.width() > rect.height() && screenWidth > screenHeight) || (rect.height() > rect.width() && screenHeight > screenWidth)) {
                    if ((rect.width() / sampleSize > screenWidth || rect.height() / sampleSize > screenHeight)) {
                        newSampleSize *= 2
                    }
                }
            }

            val options = BitmapFactory.Options()
            options.inSampleSize = newSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            val decoderWidth = decoder!!.width
            val decoderHeight = decoder!!.height
            val rectInRaw = when (orientation) {
                ORIENTATION_UNDEFINED, ORIENTATION_NORMAL -> rect
                ORIENTATION_ROTATE_270 ->
                    Rect(decoderWidth - rect.bottom, rect.left, decoderWidth - rect.top, rect.right)
                ORIENTATION_TRANSPOSE ->
                    Rect(rect.top, rect.left, rect.bottom, rect.right)
                ORIENTATION_ROTATE_180 ->
                    Rect(decoderWidth - rect.right, decoderHeight - rect.bottom, decoderWidth - rect.left, decoderHeight - rect.top)
                ORIENTATION_ROTATE_90 ->
                    Rect(rect.top, decoderHeight - rect.right, rect.bottom, decoderHeight - rect.left)
                ORIENTATION_TRANSVERSE ->
                    Rect(decoderWidth - rect.right, decoderHeight - rect.bottom, decoderWidth - rect.left, decoderHeight - rect.top)
                ORIENTATION_FLIP_HORIZONTAL ->
                    Rect(decoderWidth - rect.right, rect.top, decoderWidth - rect.left, rect.bottom)
                ORIENTATION_FLIP_VERTICAL ->
                    Rect(rect.left, decoderHeight - rect.bottom, rect.right, decoderHeight - rect.top)
                else -> rect
            }
            val bitmapInRaw = decoder!!.decodeRegion(rectInRaw, options)
                ?: throw RuntimeException("Region decoder returned null bitmap - image format may not be supported")
            val orientationMatrix = OrientationTransformation.getMatrix(orientation)
            return Bitmap.createBitmap(bitmapInRaw, 0, 0, bitmapInRaw.width, bitmapInRaw.height, orientationMatrix, true)
        }
    }

    override fun isReady() = decoder != null && !decoder!!.isRecycled

    override fun recycle() {
        decoder!!.recycle()
    }
}
