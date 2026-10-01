package tomato.gallery.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object EditorColorMatrix {
    const val SLIDER_CENTER = 100

    fun isIdentity(brightness: Int, contrast: Int, saturation: Int, temperature: Int): Boolean {
        return brightness == SLIDER_CENTER && contrast == SLIDER_CENTER && saturation == SLIDER_CENTER && temperature == SLIDER_CENTER
    }

    fun apply(source: Bitmap, brightness: Int, contrast: Int, saturation: Int, temperature: Int): Bitmap {
        val config = source.config ?: Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(source.width, source.height, config)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(matrix(brightness, contrast, saturation, temperature))
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    fun matrix(brightness: Int, contrast: Int, saturation: Int, temperature: Int): ColorMatrix {
        val b = (brightness - SLIDER_CENTER) * 1.2f
        val scale = contrast / SLIDER_CENTER.toFloat()
        val translate = (1f - scale) * 128f
        val sat = saturation / SLIDER_CENTER.toFloat()
        val temp = (temperature - SLIDER_CENTER) / SLIDER_CENTER.toFloat()

        val result = ColorMatrix()
        result.setSaturation(sat)

        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(contrastMatrix)

        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(brightnessMatrix)

        val r = 1f + temp * 0.35f
        val blue = 1f - temp * 0.35f
        val temperatureMatrix = ColorMatrix(
            floatArrayOf(
                r, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, blue, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        result.postConcat(temperatureMatrix)
        return result
    }
}
