package tomato.gallery.helpers

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import com.simplemobiletools.commons.extensions.beGone
import com.simplemobiletools.commons.extensions.beVisible
import com.simplemobiletools.commons.extensions.beVisibleIf
import kotlin.math.abs

class ActionSheetController(
    private val sheet: LinearLayout,
    private val dim: View,
    private val moreButton: View,
) {
    private val hysteresis = 10f * sheet.resources.displayMetrics.density
    private var tracking = false
    private var locked = false
    private var downY = 0f
    private var downTranslation = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var velocityY = 0f

    fun wire() {
        moreButton.setOnClickListener { if (sheet.isShown && sheet.translationY == 0f) close(false) else open() }
        dim.setOnClickListener { close(false) }
        sheet.setOnTouchListener { _, event -> onTouch(event) }
    }

    fun bind(actions: List<View>) {
        sheet.removeAllViews()
        moreButton.beVisibleIf(actions.isNotEmpty())
        if (actions.isEmpty()) {
            if (sheet.visibility == View.VISIBLE) close(false)
            return
        }
        val density = sheet.resources.displayMetrics.density
        val handle = View(sheet.context).apply {
            val width = (36 * density).toInt()
            val height = (4 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(width, height).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = (6 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
            background = GradientDrawable().apply {
                cornerRadius = height.toFloat()
                setColor(0x66FFFFFF)
            }
        }
        sheet.addView(handle)
        val pad = (16 * density).toInt()
        actions.forEach { source ->
            val row = TextView(sheet.context).apply {
                text = source.contentDescription
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0f
                gravity = Gravity.CENTER_VERTICAL
                minHeight = (48 * density).toInt()
                setPadding(pad, pad / 2, pad, pad / 2)
                setOnClickListener {
                    close(false)
                    source.performClick()
                }
            }
            sheet.addView(row)
        }
    }

    fun closeIfOpen() {
        if (sheet.visibility == View.VISIBLE) close(false)
    }

    private fun open() {
        sheet.beVisible()
        dim.beVisible()
        if (FluidMotion.isReducedMotion(sheet.context)) {
            sheet.translationY = 0f
            dim.alpha = 0.45f
            return
        }
        dim.alpha = 0f
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            FluidMotion.spring(sheet, DynamicAnimation.TRANSLATION_Y, 0f, 1f, 0.35f)
            FluidMotion.spring(dim, DynamicAnimation.ALPHA, 0.45f, 1f, 0.35f)
        }
    }

    private fun close(fromFlick: Boolean) {
        val damping = if (fromFlick) 0.8f else 1f
        val travel = sheet.height.toFloat().coerceAtLeast(1f)
        if (FluidMotion.isReducedMotion(sheet.context)) {
            sheet.beGone()
            dim.beGone()
            sheet.translationY = 0f
            dim.alpha = 0f
            return
        }
        FluidMotion.spring(dim, DynamicAnimation.ALPHA, 0f, damping, 0.3f)
        val anim = FluidMotion.spring(sheet, DynamicAnimation.TRANSLATION_Y, travel, damping, 0.3f, if (fromFlick) velocityY else null)
        if (anim == null) {
            sheet.beGone()
            dim.beGone()
            return
        }
        lateinit var listener: DynamicAnimation.OnAnimationEndListener
        listener = DynamicAnimation.OnAnimationEndListener { animation, canceled, _, _ ->
            animation.removeEndListener(listener)
            if (!canceled && sheet.translationY > travel * 0.8f) {
                sheet.beGone()
                dim.beGone()
            }
        }
        anim.addEndListener(listener)
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                locked = false
                downY = event.rawY
                downTranslation = sheet.translationY
                lastY = event.rawY
                lastTime = event.eventTime
                velocityY = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dy = event.rawY - downY
                if (!locked && abs(dy) < hysteresis) return true
                locked = true
                val dt = (event.eventTime - lastTime).coerceAtLeast(1)
                velocityY = (event.rawY - lastY) / dt * 1000f
                lastY = event.rawY
                lastTime = event.eventTime
                val raw = downTranslation + dy
                val height = sheet.height.toFloat().coerceAtLeast(1f)
                sheet.translationY = when {
                    raw < 0f -> -FluidMotion.rubberband(-raw, height)
                    raw > height -> height + FluidMotion.rubberband(raw - height, height)
                    else -> raw
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!locked) {
                    tracking = false
                    return true
                }
                tracking = false
                locked = false
                val height = sheet.height.toFloat().coerceAtLeast(1f)
                val projected = sheet.translationY + FluidMotion.project(velocityY)
                val closeSheet = if (abs(velocityY) > 200f) velocityY > 0f else projected > height * 0.5f
                if (closeSheet) close(true) else {
                    FluidMotion.spring(sheet, DynamicAnimation.TRANSLATION_Y, 0f, 0.8f, 0.3f, velocityY)
                }
                return true
            }
        }
        return false
    }
}
