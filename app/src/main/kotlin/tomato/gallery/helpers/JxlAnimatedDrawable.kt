package tomato.gallery.helpers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import com.awxkee.jxlcoder.JxlAnimatedImage
import kotlin.math.max
import kotlin.math.min

class JxlAnimatedDrawable(
    private val image: JxlAnimatedImage,
    maxEdge: Int
) : Drawable(), Animatable {
    private val displayWidth: Int
    private val displayHeight: Int

    init {
        val srcW = image.getWidth().coerceAtLeast(1)
        val srcH = image.getHeight().coerceAtLeast(1)
        val scale = min(1f, maxEdge.toFloat() / max(srcW, srcH).toFloat())
        displayWidth = (srcW * scale).toInt().coerceAtLeast(1)
        displayHeight = (srcH * scale).toInt().coerceAtLeast(1)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var index = 0
    private var frame: Bitmap? = decode(0)
    private val tick = object : Runnable {
        override fun run() {
            if (!running) {
                return
            }
            val count = image.numberOfFrames.coerceAtLeast(1)
            index = (index + 1) % count
            val next = decode(index)
            val old = frame
            frame = next
            if (old != null && old !== next && !old.isRecycled) {
                old.recycle()
            }
            invalidateSelf()
            val delay = image.getFrameDuration(index).toLong().coerceAtLeast(16L)
            handler.postDelayed(this, delay)
        }
    }

    private fun decode(frameIndex: Int): Bitmap {
        return image.getFrame(frameIndex, displayWidth, displayHeight)
    }

    override fun draw(canvas: Canvas) {
        val bmp = frame ?: return
        canvas.drawBitmap(bmp, null, bounds, null)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth() = if (displayWidth > 0) displayWidth else image.getWidth()

    override fun getIntrinsicHeight() = if (displayHeight > 0) displayHeight else image.getHeight()

    override fun start() {
        if (running || image.numberOfFrames <= 1) {
            return
        }
        running = true
        handler.post(tick)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    override fun isRunning() = running

    fun release() {
        stop()
        image.close()
        frame = null
    }
}
