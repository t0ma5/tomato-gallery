package tomato.gallery.helpers

import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import kotlin.math.abs

interface ViewerMotionHost {
    fun onViewerTouch(event: MotionEvent): Boolean
}

/**
 * 1:1 drag-to-dismiss and thumbnail container transform.
 * Springs start from the live translation. A second grab cancels the spring and follows the finger.
 */
class ViewerMotion(
    private val activity: Activity,
    private val content: View,
    private val scrim: View?,
    private val currentPath: () -> String,
    private val exitRect: (String) -> Rect?,
    private val allowOffscreenExit: Boolean = true,
) {
    private val hysteresis = 10f * activity.resources.displayMetrics.density
    private var tracking = false
    private var locked = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTranslation = 0f
    private var velocityY = 0f
    private var interrupted = false
    private val velocity = FlingVelocity(activity)
    private var exiting = false
    val isExiting: Boolean get() = exiting
    private var lead: SpringAnimation? = null
    private var exitListener: DynamicAnimation.OnAnimationEndListener? = null

    fun playEnter(rect: Rect?, attempt: Int = 0) {
        if (rect == null) return
        if (content.width == 0) {
            if (attempt < 8) content.post { playEnter(rect, attempt + 1) }
            return
        }
        if (FluidMotion.isReducedMotion(activity)) {
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(200).start()
            scrim?.alpha = 0f
            scrim?.animate()?.alpha(1f)?.setDuration(200)?.start()
            return
        }
        val loc = IntArray(2)
        content.getLocationOnScreen(loc)
        val scale = rect.width() / content.width.toFloat().coerceAtLeast(1f)
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale
        content.translationX = rect.left - loc[0].toFloat()
        content.translationY = rect.top - loc[1].toFloat()
        scrim?.alpha = 0f
        settle(translationX = 0f, translationY = 0f, scale = 1f, scrimAlpha = 1f, damping = 1f, response = 0.4f, velocityY = 0f, finish = false)
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (exiting) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSprings()
                stopContentSprings()
                interrupted = !isAtRest()
                tracking = true
                locked = false
                downRawX = event.rawX
                downRawY = event.rawY
                downTranslation = content.translationY
                velocityY = 0f
                velocity.start(event)
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (locked || interrupted) settleBack()
                tracking = false
                locked = false
                interrupted = false
                velocity.clear()
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                velocity.move(event)
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!locked) {
                    if (abs(dy) < hysteresis || abs(dy) <= abs(dx)) return false
                    locked = true
                    content.parent?.requestDisallowInterceptTouchEvent(true)
                }
                val raw = downTranslation + dy
                content.translationY = if (raw < 0f) {
                    -FluidMotion.rubberband(-raw, content.height.toFloat().coerceAtLeast(1f))
                } else {
                    raw
                }
                val progress = (content.translationY / content.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                scrim?.alpha = 1f - progress
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasLocked = locked
                val wasInterrupted = interrupted
                tracking = false
                locked = false
                interrupted = false
                if (!wasLocked) {
                    velocity.clear()
                    if (wasInterrupted) settleBack()
                    return false
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    velocity.clear()
                    velocityY = 0f
                    settleBack()
                    return true
                }
                velocityY = velocity.releaseY(event)
                val projected = content.translationY + FluidMotion.project(velocityY)
                val height = content.height.toFloat().coerceAtLeast(1f)
                val dismiss = if (velocity.isFling(velocityY)) velocityY > 0f else projected > height * 0.5f
                if (dismiss) dismissFromDrag() else settleBack()
                return true
            }
        }
        return false
    }

    fun cancel() {
        if (locked || interrupted) settleBack()
        tracking = false
        locked = false
        interrupted = false
        velocity.clear()
    }

    /** @return true when the exit animation owns finish(). */
    fun interceptFinish(onEnd: () -> Unit): Boolean {
        if (exiting || !content.isLaidOut) return false
        val rect = exitRect(currentPath())
        if (rect == null && !allowOffscreenExit) return false
        exiting = true
        cancelSprings()
        if (FluidMotion.isReducedMotion(activity)) {
            content.animate().alpha(0f).setDuration(200).withEndAction(onEnd).start()
            scrim?.animate()?.alpha(0f)?.setDuration(200)?.start()
            return true
        }
        if (rect != null && content.width > 0) {
            springToRect(rect, damping = 1f, velocity = 0f, onEnd = onEnd)
        } else {
            settle(
                translationX = content.translationX,
                translationY = content.height.toFloat(),
                scale = content.scaleX.coerceAtMost(1f),
                scrimAlpha = 0f,
                damping = 1f,
                response = 0.35f,
                velocityY = 0f,
                finish = true,
                onEnd = onEnd,
            )
        }
        return true
    }

    private fun settleBack() {
        settle(
            translationX = 0f,
            translationY = 0f,
            scale = 1f,
            scrimAlpha = 1f,
            damping = 0.8f,
            response = 0.3f,
            velocityY = velocityY,
            finish = false,
        )
    }

    private fun dismissFromDrag() {
        exiting = true
        val rect = exitRect(currentPath())
        if (rect != null && content.width > 0) {
            springToRect(rect, damping = 0.8f, velocity = velocityY, onEnd = { activity.finish() })
        } else {
            settle(
                translationX = 0f,
                translationY = content.height.toFloat(),
                scale = 1f,
                scrimAlpha = 0f,
                damping = 0.8f,
                response = 0.3f,
                velocityY = velocityY,
                finish = true,
                onEnd = { activity.finish() },
            )
        }
    }

    private fun springToRect(rect: Rect, damping: Float, velocity: Float, onEnd: () -> Unit) {
        val loc = IntArray(2)
        content.getLocationOnScreen(loc)
        val scale = rect.width() / content.width.toFloat().coerceAtLeast(1f)
        content.pivotX = 0f
        content.pivotY = 0f
        val targetX = content.translationX + (rect.left - loc[0])
        val targetY = content.translationY + (rect.top - loc[1])
        settle(targetX, targetY, scale, 0f, damping, 0.35f, velocity, true, onEnd)
    }

    private fun settle(
        translationX: Float,
        translationY: Float,
        scale: Float,
        scrimAlpha: Float,
        damping: Float,
        response: Float,
        velocityY: Float,
        finish: Boolean,
        onEnd: (() -> Unit)? = null,
    ) {
        val reduced = FluidMotion.isReducedMotion(activity)
        FluidMotion.spring(content, DynamicAnimation.TRANSLATION_X, translationX, damping, response, reduced = reduced)
        FluidMotion.spring(content, DynamicAnimation.SCALE_X, scale, damping, response, reduced = reduced)
        FluidMotion.spring(content, DynamicAnimation.SCALE_Y, scale, damping, response, reduced = reduced)
        scrim?.let { FluidMotion.spring(it, DynamicAnimation.ALPHA, scrimAlpha, damping, response, reduced = reduced) }
        val anim = FluidMotion.spring(content, DynamicAnimation.TRANSLATION_Y, translationY, damping, response, velocityY, reduced)
        if (!finish) return
        if (anim == null) {
            onEnd?.invoke()
            return
        }
        lead = anim
        exitListener?.let { anim.removeEndListener(it) }
        lateinit var listener: DynamicAnimation.OnAnimationEndListener
        listener = DynamicAnimation.OnAnimationEndListener { animation, canceled, _, _ ->
            animation.removeEndListener(listener)
            if (animation !== lead || canceled) return@OnAnimationEndListener
            onEnd?.invoke()
        }
        exitListener = listener
        anim.addEndListener(listener)
    }

    private fun cancelSprings() {
        lead?.cancel()
        lead = null
    }

    private fun stopContentSprings() {
        FluidMotion.cancel(
            content,
            DynamicAnimation.TRANSLATION_X,
            DynamicAnimation.TRANSLATION_Y,
            DynamicAnimation.SCALE_X,
            DynamicAnimation.SCALE_Y,
        )
        scrim?.let { FluidMotion.cancel(it, DynamicAnimation.ALPHA) }
    }

    private fun isAtRest(): Boolean {
        return content.translationX == 0f && content.translationY == 0f &&
            content.scaleX == 1f && content.scaleY == 1f && (scrim == null || scrim.alpha == 1f)
    }
}
