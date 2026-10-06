package tomato.gallery.helpers

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs

object FluidMotion {
    private val springs = WeakHashMap<View, HashMap<FloatPropertyCompat<View>, SpringAnimation>>()

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
    ): SpringAnimation? {
        if (isReducedMotion(view.context)) {
            property.setValue(view, to)
            return null
        }
        val byProperty = springs.getOrPut(view) { HashMap() }
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
