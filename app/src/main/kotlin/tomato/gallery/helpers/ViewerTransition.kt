package tomato.gallery.helpers

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

object ViewerTransition {
    private var pendingRect: Rect? = null
    private var pendingPath: String? = null
    private var photoOwner: Any? = null
    private var photoLocator: ((String) -> Rect?)? = null
    private var folderOwner: Any? = null
    private var folderLocator: ((String) -> Rect?)? = null

    fun offer(view: View, path: String) {
        if (view.width <= 0 || view.height <= 0) return
        pendingRect = rectOf(view)
        pendingPath = path
    }

    fun takePending(path: String): Rect? {
        if (pendingPath != path) return null
        val rect = pendingRect
        pendingRect = null
        pendingPath = null
        return rect
    }

    fun bindPhotoLocator(owner: Any, locator: (String) -> Rect?) {
        photoOwner = owner
        photoLocator = locator
    }

    fun unbindPhotoLocator(owner: Any) {
        if (photoOwner == owner) {
            photoOwner = null
            photoLocator = null
        }
    }

    fun bindFolderLocator(owner: Any, locator: (String) -> Rect?) {
        folderOwner = owner
        folderLocator = locator
    }

    fun unbindFolderLocator(owner: Any) {
        if (folderOwner == owner) {
            folderOwner = null
            folderLocator = null
        }
    }

    fun photoRect(path: String): Rect? = if (path.isEmpty()) null else photoLocator?.invoke(path)

    fun folderRect(path: String): Rect? = if (path.isEmpty()) null else folderLocator?.invoke(path)

    fun rectOf(view: View): Rect {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    fun findThumb(recycler: RecyclerView, thumbnailId: Int, path: String, pathAt: (Int) -> String?): View? {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChildAt(i)
            val pos = recycler.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            if (pathAt(pos) == path) {
                return child.findViewById(thumbnailId) ?: child
            }
        }
        return null
    }
}
