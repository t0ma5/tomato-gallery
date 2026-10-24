package tomato.gallery.helpers

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import tomato.gallery.R
import kotlin.math.PI
import kotlin.math.abs

object FluidMotion {
    fun isReducedMotion(context: Context): Boolean {
        val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        return scale == 0f
    }

    fun reducedTransparency(context: Context): Boolean {
        return Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    }

    fun rubberband(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }

    /** Apple exponential-decay projection. Velocity is px/s. Result is px of travel. */
    fun project(initialVelocityPxPerSec: Float, decelerationRate: Float = 0.998f): Float {
        return (initialVelocityPxPerSec / 1000f) * decelerationRate / (1f - decelerationRate)
    }

    fun spring(
        view: View,
        property: FloatPropertyCompat<View>,
        to: Float,
        damping: Float,
        response: Float,
        velocity: Float? = null,
        reduced: Boolean = isReducedMotion(view.context),
    ): SpringAnimation? {
        if (reduced) {
            springsOf(view, false)?.get(property)?.cancel()
            property.setValue(view, to)
            return null
        }
        val byProperty = springsOf(view, true)!!
        val anim = byProperty.getOrPut(property) { SpringAnimation(view, property) }
        val omega = (2.0 * PI / response.toDouble()).toFloat()
        val force = anim.spring ?: SpringForce().also { anim.spring = it }
        force.dampingRatio = damping
        force.stiffness = omega * omega
        force.finalPosition = to
        if (velocity != null) {
            anim.setStartVelocity(velocity)
        }
        anim.animateToFinalPosition(to)
        return anim
    }

    fun cancel(view: View, vararg properties: FloatPropertyCompat<View>) {
        val byProperty = springsOf(view, false) ?: return
        properties.forEach { byProperty[it]?.cancel() }
    }

    fun cancelAll(view: View) {
        springsOf(view, false)?.values?.forEach { it.cancel() }
    }

    // Springs live in a view tag (not a static map) so they die with the view; detaching snaps them to their target.
    @Suppress("UNCHECKED_CAST")
    private fun springsOf(view: View, create: Boolean): HashMap<FloatPropertyCompat<View>, SpringAnimation>? {
        val existing = view.getTag(R.id.fluid_springs) as? HashMap<FloatPropertyCompat<View>, SpringAnimation>
        if (existing != null || !create) return existing
        val created = HashMap<FloatPropertyCompat<View>, SpringAnimation>()
        view.setTag(R.id.fluid_springs, created)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}

            override fun onViewDetachedFromWindow(v: View) {
                created.forEach { (property, anim) ->
                    if (anim.isRunning) {
                        anim.cancel()
                        anim.spring?.let { property.setValue(v, it.finalPosition) }
                    }
                }
            }
        })
        return created
    }

    fun attachPress(view: View) {
        if (view.getTag(tomato.gallery.R.id.press_feedback) == true) return
        view.setTag(tomato.gallery.R.id.press_feedback, true)
        val slop = 10f * view.resources.displayMetrics.density
        var pressed = false
        view.setOnTouchListener { v, event ->
            val inside = event.x >= -slop && event.y >= -slop && event.x <= v.width + slop && event.y <= v.height + slop
            val want = event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL &&
                (event.actionMasked == MotionEvent.ACTION_DOWN || inside)
            if (want != pressed) {
                pressed = want
                val target = if (want) 0.97f else 1f
                spring(v, DynamicAnimation.SCALE_X, target, 1f, 0.12f)
                spring(v, DynamicAnimation.SCALE_Y, target, 1f, 0.12f)
            }
            false
        }
    }

    fun popIn(view: View) {
        if (isReducedMotion(view.context)) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.scaleX = 0.6f
        view.scaleY = 0.6f
        spring(view, DynamicAnimation.SCALE_X, 1f, 1f, 0.28f)
        spring(view, DynamicAnimation.SCALE_Y, 1f, 1f, 0.28f)
    }

    fun markSelection(view: View, selected: Boolean) {
        val was = view.getTag(tomato.gallery.R.id.motion_selected) as? Boolean ?: false
        view.setTag(tomato.gallery.R.id.motion_selected, selected)
        if (selected && !was) popIn(view)
    }

    fun confirmHaptic(view: View) {
        val constant = if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant)
    }
}

/** Lift velocity from a VelocityTracker on raw coordinates. A finger that rested before lifting reads as 0. */
class FlingVelocity(context: Context) {
    private val density = context.resources.displayMetrics.density
    private var tracker: VelocityTracker? = null
    private var lastMoveTime = 0L

    fun start(event: MotionEvent) {
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
        lastMoveTime = event.eventTime
        add(event)
    }

    fun move(event: MotionEvent) {
        lastMoveTime = event.eventTime
        add(event)
    }

    /** px/s on the Y axis at lift; call with the ACTION_UP event. */
    fun releaseY(event: MotionEvent): Float {
        val t = tracker ?: return 0f
        if (event.eventTime - lastMoveTime > STALE_MS) {
            clear()
            return 0f
        }
        add(event)
        t.computeCurrentVelocity(1000)
        val velocity = t.yVelocity
        clear()
        return if (velocity.isNaN()) 0f else velocity
    }

    fun isFling(velocityPxPerSec: Float) = abs(velocityPxPerSec) > FLING_DP_PER_SEC * density

    fun clear() {
        tracker?.recycle()
        tracker = null
    }

    private fun add(event: MotionEvent) {
        val t = tracker ?: return
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        t.addMovement(copy)
        copy.recycle()
    }

    private companion object {
        const val STALE_MS = 80L
        const val FLING_DP_PER_SEC = 200f
    }
}
