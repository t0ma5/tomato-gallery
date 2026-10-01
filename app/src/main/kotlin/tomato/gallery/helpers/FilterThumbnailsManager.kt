package tomato.gallery.helpers

import android.graphics.Bitmap
import tomato.gallery.models.FilterItem

class FilterThumbnailsManager {
    private var filterThumbnails = ArrayList<FilterItem>(10)
    private var processedThumbnails = ArrayList<FilterItem>(10)

    fun addThumb(filterItem: FilterItem) {
        filterThumbnails.add(filterItem)
    }

    fun processThumbs(): ArrayList<FilterItem> {
        for (filterItem in filterThumbnails) {
            val src = filterItem.bitmap
            if (src.isRecycled || src.width <= 0 || src.height <= 0) {
                continue
            }
            filterItem.bitmap = filterItem.filter.processFilter(Bitmap.createBitmap(src))
            processedThumbnails.add(filterItem)
        }
        return processedThumbnails
    }

    fun clearThumbs() {
        filterThumbnails = ArrayList()
        processedThumbnails = ArrayList()
    }
}
