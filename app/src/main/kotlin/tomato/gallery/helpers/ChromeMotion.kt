package tomato.gallery.helpers

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation

object ChromeMotion {
    fun slide(view: View, hidden: Boolean, upward: Boolean, distance: Float? = null) {
        view.post {
            val travel = distance?.takeIf { it > 0f }
                ?: view.height.toFloat().takeIf { it > 0f }
                ?: (56f * view.resources.displayMetrics.density)
            val target = if (hidden) (if (upward) -travel else travel) else 0f
            if (FluidMotion.isReducedMotion(view.context)) {
                view.translationY = 0f
                view.alpha = if (hidden) 0f else 1f
            } else {
                view.alpha = 1f
                FluidMotion.spring(view, DynamicAnimation.TRANSLATION_Y, target, 1f, 0.35f)
            }
        }
    }
}

object SkipHint {
    fun show(backward: View, forward: View, skipForward: Boolean) {
        val view = if (skipForward) forward else backward
        val other = if (skipForward) backward else forward
        other.animate().cancel()
        other.alpha = 0f
        view.animate().cancel()
        val nudge = 18f * view.resources.displayMetrics.density
        view.alpha = 0f
        view.translationX = if (skipForward) -nudge else nudge
        if (FluidMotion.isReducedMotion(view.context)) {
            view.alpha = 1f
            view.translationX = 0f
            view.animate().alpha(0f).setStartDelay(220).setDuration(160).start()
            return
        }
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(140)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate().alpha(0f).setStartDelay(160).setDuration(220).start()
            }
            .start()
    }
}
