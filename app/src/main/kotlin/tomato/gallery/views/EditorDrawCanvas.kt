package tomato.gallery.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.simplemobiletools.commons.extensions.getProperPrimaryColor
import tomato.gallery.R
import tomato.gallery.models.PaintOptions

class EditorDrawCanvas(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private var mCurX = 0f
    private var mCurY = 0f
    private var mStartX = 0f
    private var mStartY = 0f
    private var mColor = 0
    private var mWasMultitouch = false

    private var mPaths = LinkedHashMap<Path, PaintOptions>()
    private var mPaint = Paint()
    private var mPath = Path()
    private var mPaintOptions = PaintOptions()

    private var backgroundBitmap: Bitmap? = null

    init {
        mColor = context.getProperPrimaryColor()
        mPaint.apply {
            color = mColor
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 40f
            isAntiAlias = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        val background = backgroundBitmap
        if (background != null) {
            canvas.drawBitmap(background, null, fittedDestRect(background), null)
        }

        for ((key, value) in mPaths) {
            changePaint(value)
            canvas.drawPath(key, mPaint)
        }

        changePaint(mPaintOptions)
        canvas.drawPath(mPath, mPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                mWasMultitouch = false
                mStartX = x
                mStartY = y
                actionDown(x, y)
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && !mWasMultitouch) {
                    actionMove(x, y)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> actionUp()
            MotionEvent.ACTION_POINTER_DOWN -> mWasMultitouch = true
        }

        invalidate()
        return true
    }

    private fun actionDown(x: Float, y: Float) {
        mPath.reset()
        mPath.moveTo(x, y)
        mCurX = x
        mCurY = y
    }

    private fun actionMove(x: Float, y: Float) {
        mPath.quadTo(mCurX, mCurY, (x + mCurX) / 2, (y + mCurY) / 2)
        mCurX = x
        mCurY = y
    }

    private fun actionUp() {
        if (!mWasMultitouch) {
            mPath.lineTo(mCurX, mCurY)

            // draw a dot on click
            if (mStartX == mCurX && mStartY == mCurY) {
                mPath.lineTo(mCurX, mCurY + 2)
                mPath.lineTo(mCurX + 1, mCurY + 2)
                mPath.lineTo(mCurX + 1, mCurY)
            }
        }

        mPaths[mPath] = mPaintOptions
        mPath = Path()
        mPaintOptions = PaintOptions(mPaintOptions.color, mPaintOptions.strokeWidth)
    }

    private fun changePaint(paintOptions: PaintOptions) {
        mPaint.color = paintOptions.color
        mPaint.strokeWidth = paintOptions.strokeWidth
    }

    fun updateColor(newColor: Int) {
        mPaintOptions.color = newColor
    }

    fun updateBrushSize(newBrushSize: Int) {
        mPaintOptions.strokeWidth = resources.getDimension(R.dimen.full_brush_size) * (newBrushSize / 100f)
    }

    fun updateBackgroundBitmap(bitmap: Bitmap) {
        backgroundBitmap = bitmap
        invalidate()
    }

    fun clearDrawing() {
        mPaths.clear()
        mPath.reset()
        invalidate()
    }

    fun hasDrawing() = mPaths.isNotEmpty()

    fun getBitmap(target: Bitmap? = backgroundBitmap): Bitmap {
        val src = target ?: backgroundBitmap
        if (src != null && !src.isRecycled) {
            val out = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val dest = fittedDestRect(src)
            if (dest.width() > 0f && dest.height() > 0f) {
                canvas.scale(src.width / dest.width(), src.height / dest.height())
                canvas.translate(-dest.left, -dest.top)
                for ((key, value) in mPaths) {
                    changePaint(value)
                    canvas.drawPath(key, mPaint)
                }
                changePaint(mPaintOptions)
                canvas.drawPath(mPath, mPaint)
            }
            return out
        }
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return bitmap
    }

    private fun fittedDestRect(bitmap: Bitmap): RectF {
        val scale = minOf(width.toFloat() / bitmap.width.coerceAtLeast(1), height.toFloat() / bitmap.height.coerceAtLeast(1))
        val destWidth = bitmap.width * scale
        val destHeight = bitmap.height * scale
        val left = (width - destWidth) / 2f
        val top = (height - destHeight) / 2f
        return RectF(left, top, left + destWidth, top + destHeight)
    }

    fun undo() {
        if (mPaths.isEmpty()) {
            return
        }

        val lastKey = mPaths.keys.lastOrNull()
        mPaths.remove(lastKey)
        invalidate()
    }
}
