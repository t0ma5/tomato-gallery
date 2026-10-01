package tomato.gallery.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color

object CollageBuilder {
    const val LAYOUT_HORIZONTAL = 0
    const val LAYOUT_VERTICAL = 1
    const val LAYOUT_GRID_2X2 = 2

    fun compose(bitmaps: List<Bitmap>, layout: Int, gapPx: Int = 8, background: Int = Color.WHITE): Bitmap {
        val images = bitmaps.filter { !it.isRecycled }.take(4)
        require(images.size >= 2) { "Need at least 2 images" }
        return when (layout) {
            LAYOUT_VERTICAL -> stack(images, vertical = true, gapPx, background)
            LAYOUT_GRID_2X2 -> grid2x2(images, gapPx, background)
            else -> stack(images.take(2), vertical = false, gapPx, background)
        }
    }

    fun decodeDownsampled(path: String, maxEdge: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (largest / sample > maxEdge) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun stack(images: List<Bitmap>, vertical: Boolean, gapPx: Int, background: Int): Bitmap {
        val target = if (vertical) images.maxOf { it.width } else images.maxOf { it.height }
        val scaled = images.map { scaleTo(it, if (vertical) target to 0 else 0 to target, vertical) }
        val width = if (vertical) target else scaled.sumOf { it.width } + gapPx * (scaled.size - 1)
        val height = if (vertical) scaled.sumOf { it.height } + gapPx * (scaled.size - 1) else target
        val out = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        var cursor = 0
        scaled.forEach { bmp ->
            if (vertical) {
                val x = ((width - bmp.width) / 2).coerceAtLeast(0)
                canvas.drawBitmap(bmp, x.toFloat(), cursor.toFloat(), null)
                cursor += bmp.height + gapPx
            } else {
                val y = ((height - bmp.height) / 2).coerceAtLeast(0)
                canvas.drawBitmap(bmp, cursor.toFloat(), y.toFloat(), null)
                cursor += bmp.width + gapPx
            }
            if (bmp !== images.find { it === bmp }) {
                // scaled copies only
            }
        }
        scaled.filter { src -> images.none { it === src } }.forEach { if (!it.isRecycled) it.recycle() }
        return out
    }

    private fun grid2x2(images: List<Bitmap>, gapPx: Int, background: Int): Bitmap {
        val cellW = images.take(4).maxOf { it.width }.coerceAtMost(2048)
        val cellH = images.take(4).maxOf { it.height }.coerceAtMost(2048)
        val cols = 2
        val rows = if (images.size > 2) 2 else 1
        val width = cellW * cols + gapPx * (cols - 1)
        val height = cellH * rows + gapPx * (rows - 1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        images.take(4).forEachIndexed { index, src ->
            val col = index % 2
            val row = index / 2
            val fitted = fitCenter(src, cellW, cellH)
            val x = col * (cellW + gapPx) + (cellW - fitted.width) / 2
            val y = row * (cellH + gapPx) + (cellH - fitted.height) / 2
            canvas.drawBitmap(fitted, x.toFloat(), y.toFloat(), null)
            if (fitted !== src && !fitted.isRecycled) {
                fitted.recycle()
            }
        }
        return out
    }

    private fun scaleTo(src: Bitmap, target: Pair<Int, Int>, matchWidth: Boolean): Bitmap {
        val (w, h) = if (matchWidth) {
            val width = target.first
            width to (src.height * (width.toFloat() / src.width)).toInt().coerceAtLeast(1)
        } else {
            val height = target.second
            (src.width * (height.toFloat() / src.height)).toInt().coerceAtLeast(1) to height
        }
        return if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun fitCenter(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val scale = minOf(maxW / src.width.toFloat(), maxH / src.height.toFloat(), 1f)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
    }
}
