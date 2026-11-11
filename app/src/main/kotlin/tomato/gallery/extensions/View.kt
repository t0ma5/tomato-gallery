package tomato.gallery.extensions

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat

fun View.sendFakeClick(x: Float, y: Float) {
    val uptime = SystemClock.uptimeMillis()
    val event = MotionEvent.obtain(uptime, uptime, MotionEvent.ACTION_DOWN, x, y, 0)
    dispatchTouchEvent(event)
    event.action = MotionEvent.ACTION_UP
    dispatchTouchEvent(event)
}

// TalkBack: keep reading the view's text, but announce what a tap does ("double-tap to skip back")
fun View.setClickActionLabel(label: CharSequence) {
    ViewCompat.replaceAccessibilityAction(this, AccessibilityActionCompat.ACTION_CLICK, label, null)
}
