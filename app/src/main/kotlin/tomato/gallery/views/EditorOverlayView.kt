package tomato.gallery.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.simplemobiletools.commons.extensions.getProperPrimaryColor
import tomato.gallery.helpers.EditorFonts
import kotlin.math.abs
import kotlin.math.atan2

class EditorOverlayView(context: Context, attrs: AttributeSet) : FrameLayout(context, attrs) {
    private val backgroundView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    private val overlays = mutableListOf<TextView>()
    private var selectedOverlay: TextView? = null
    private var backgroundBitmap: Bitmap? = null
    var overlayColor: Int = context.getProperPrimaryColor()
    var overlayTextSizeSp = 28f
    var overlayFontId = 0
    private var overlayTypeface: Typeface = EditorFonts.typeface(0)
    var onOverlayChanged: ((rotationDegrees: Int) -> Unit)? = null
    var onEditSelectedOverlay: ((currentText: String) -> Unit)? = null

    private var rotateStartAngle = 0f
    private var rotateStartRotation = 0f

    init {
        addView(backgroundView)
        clipChildren = false
        clipToPadding = false
    }

    fun updateBackgroundBitmap(bitmap: Bitmap) {
        backgroundBitmap = bitmap
        backgroundView.setImageBitmap(bitmap)
    }

    fun addLabel(text: String) {
        val tv = TextView(context).apply {
            this.text = text
            typeface = overlayTypeface
            setTextColor(overlayColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, overlayTextSizeSp)
            setShadowLayer(6f, 1f, 1f, Color.BLACK)
            setPadding(16, 8, 16, 8)
            rotation = 0f
        }
        addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        overlays.add(tv)
        selectedOverlay = tv
        attachDrag(tv)
        post {
            tv.x = ((width - tv.width) / 2f).coerceAtLeast(0f)
            tv.y = ((height - tv.height) / 2f).coerceAtLeast(0f)
            centerPivot(tv)
        }
        notifyOverlayChanged()
    }

    fun undo() {
        val last = overlays.removeLastOrNull() ?: return
        if (selectedOverlay === last) {
            selectedOverlay = overlays.lastOrNull()
        }
        removeView(last)
        notifyOverlayChanged()
    }

    fun clearOverlays() {
        overlays.forEach { removeView(it) }
        overlays.clear()
        selectedOverlay = null
        notifyOverlayChanged()
    }

    fun hasOverlays() = overlays.isNotEmpty()

    fun applySizeToSelected(percent: Int) {
        val tv = selectedOverlay ?: overlays.lastOrNull() ?: return
        overlayTextSizeSp = textSizeSpForPercent(percent)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, overlayTextSizeSp)
        tv.post { centerPivot(tv) }
    }

    fun updateSelectedText(text: String) {
        val tv = selectedOverlay ?: return
        tv.text = text
        tv.post { centerPivot(tv) }
        notifyOverlayChanged()
    }

    fun applyColorToSelected(color: Int) {
        overlayColor = color
        val tv = selectedOverlay ?: overlays.lastOrNull() ?: return
        tv.setTextColor(color)
    }

    fun applyFontToSelected(fontId: Int) {
        overlayFontId = fontId
        overlayTypeface = EditorFonts.typeface(fontId)
        val tv = selectedOverlay ?: overlays.lastOrNull() ?: return
        tv.typeface = overlayTypeface
        tv.post { centerPivot(tv) }
    }

    fun applyRotationToSelected(degrees: Int) {
        val tv = selectedOverlay ?: overlays.lastOrNull() ?: return
        tv.rotation = normalizeDegrees(degrees.toFloat())
        centerPivot(tv)
    }

    fun selectedRotationDegrees(): Int {
        val rotation = selectedOverlay?.rotation ?: overlays.lastOrNull()?.rotation ?: 0f
        return normalizeDegrees(rotation).toInt()
    }

    fun getBitmap(target: Bitmap? = backgroundBitmap): Bitmap {
        val src = target ?: backgroundBitmap
        if (src != null && !src.isRecycled && width > 0 && height > 0) {
            val out = src.copy(src.config ?: Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(out)
            val scale = minOf(width.toFloat() / src.width.coerceAtLeast(1), height.toFloat() / src.height.coerceAtLeast(1))
            val destWidth = src.width * scale
            val destHeight = src.height * scale
            val left = (width - destWidth) / 2f
            val top = (height - destHeight) / 2f
            if (destWidth > 0f && destHeight > 0f) {
                canvas.scale(src.width / destWidth, src.height / destHeight)
                canvas.translate(-left, -top)
                backgroundView.visibility = GONE
                draw(canvas)
                backgroundView.visibility = VISIBLE
            }
            return out
        }
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        return bitmap
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (selectedOverlay != null && ev.pointerCount >= 2) {
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val overlay = selectedOverlay ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount >= 2) {
                    rotateStartAngle = twoFingerAngle(event)
                    rotateStartRotation = overlay.rotation
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val delta = twoFingerAngle(event) - rotateStartAngle
                    overlay.rotation = normalizeDegrees(rotateStartRotation + delta)
                    centerPivot(overlay)
                    notifyOverlayChanged()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun attachDrag(view: View) {
        var dX = 0f
        var dY = 0f
        var dragged = false
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (dragged) {
                    return false
                }
                val tv = view as? TextView ?: return false
                selectedOverlay = tv
                onEditSelectedOverlay?.invoke(tv.text?.toString().orEmpty())
                return true
            }
        })
        view.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    dragged = false
                    selectedOverlay = v as? TextView
                    v.bringToFront()
                    notifyOverlayChanged()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val newX = event.rawX + dX
                        val newY = event.rawY + dY
                        if (abs(newX - v.x) > touchSlop || abs(newY - v.y) > touchSlop) {
                            dragged = true
                        }
                        val cx = (newX + v.width / 2f).coerceIn(0f, width.toFloat())
                        val cy = (newY + v.height / 2f).coerceIn(0f, height.toFloat())
                        v.x = cx - v.width / 2f
                        v.y = cy - v.height / 2f
                    }
                }
            }
            true
        }
    }

    private fun textSizeSpForPercent(percent: Int): Float {
        val p = percent.coerceIn(0, 100)
        return if (p <= 40) {
            12f + p * 0.4f
        } else {
            28f + (p - 40) * (62f / 60f)
        }
    }

    private fun centerPivot(view: View) {
        if (view.width > 0 && view.height > 0) {
            view.pivotX = view.width / 2f
            view.pivotY = view.height / 2f
        }
    }

    private fun notifyOverlayChanged() {
        onOverlayChanged?.invoke(selectedRotationDegrees())
    }

    private fun twoFingerAngle(event: MotionEvent): Float {
        val dx = rawX(event, 1) - rawX(event, 0)
        val dy = rawY(event, 1) - rawY(event, 0)
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun rawX(event: MotionEvent, index: Int): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            event.getRawX(index)
        } else {
            event.rawX - event.x + event.getX(index)
        }
    }

    private fun rawY(event: MotionEvent, index: Int): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            event.getRawY(index)
        } else {
            event.rawY - event.y + event.getY(index)
        }
    }

    private fun normalizeDegrees(degrees: Float): Float {
        var value = degrees % 360f
        if (value < 0f) {
            value += 360f
        }
        return value
    }
}
