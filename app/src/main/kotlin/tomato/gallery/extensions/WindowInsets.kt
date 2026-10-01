package tomato.gallery.extensions

import android.app.Activity
import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.core.view.updatePadding

private fun View.basePadding(): Rect {
    val stored = getTag(tomato.gallery.R.id.view_base_padding) as? Rect
    if (stored != null) {
        return stored
    }

    val rect = Rect(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setTag(tomato.gallery.R.id.view_base_padding, rect)
    return rect
}

fun View.updatePaddingWithBase(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
    val base = basePadding()
    updatePadding(
        left = base.left + left,
        top = base.top + top,
        right = base.right + right,
        bottom = base.bottom + bottom
    )
}

fun Activity.setupEdgeToEdge(
    padTopSystem: List<View> = emptyList(),
    padBottomSystem: List<View> = emptyList(),
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
        val system = insets.getInsetsIgnoringVisibility(Type.systemBars())
        padTopSystem.forEach { it.updatePaddingWithBase(top = system.top) }
        padBottomSystem.forEach { it.updatePaddingWithBase(bottom = system.bottom) }
        insets
    }
    content.requestApplyInsets()
}
