package tomato.gallery.asynctasks

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.simplemobiletools.commons.helpers.FAVORITES
import com.simplemobiletools.commons.helpers.SORT_BY_DATE_MODIFIED
import com.simplemobiletools.commons.helpers.SORT_BY_DATE_TAKEN
import com.simplemobiletools.commons.helpers.SORT_BY_SIZE
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import tomato.gallery.extensions.config
import tomato.gallery.extensions.getFavoritePaths
import tomato.gallery.helpers.*
import tomato.gallery.models.Medium
import tomato.gallery.models.ThumbnailItem

class GetMediaAsynctask(
    val context: Context, val mPath: String, val isPickImage: Boolean = false, val isPickVideo: Boolean = false,
    val showAll: Boolean, val favoritesOnly: Boolean = false, val callback: (media: ArrayList<ThumbnailItem>) -> Unit
) {
    private val mediaFetcher = MediaFetcher(context)
    @Volatile
    private var cancelled = false

    fun execute() {
        ensureBackgroundThread {
            if (cancelled) {
                return@ensureBackgroundThread
            }
            val media = try {
                fetch()
            } catch (_: Exception) {
                ArrayList()
            }
            if (!cancelled) {
                Handler(Looper.getMainLooper()).post { callback(media) }
            }
        }
    }

    private fun fetch(): ArrayList<ThumbnailItem> {
        val pathToUse = if (showAll) SHOW_ALL else mPath
        val folderGrouping = context.config.getFolderGrouping(pathToUse)
        val folderSorting = context.config.getFolderSorting(pathToUse)
        val getProperDateTaken = folderSorting and SORT_BY_DATE_TAKEN != 0 ||
            folderGrouping and GROUP_BY_DATE_TAKEN_DAILY != 0 ||
            folderGrouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0

        val getProperLastModified = folderSorting and SORT_BY_DATE_MODIFIED != 0 ||
            folderGrouping and GROUP_BY_LAST_MODIFIED_DAILY != 0 ||
            folderGrouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0

        val getProperFileSize = folderSorting and SORT_BY_SIZE != 0
        val favoritePaths = context.getFavoritePaths()
        val getVideoDurations = context.config.showThumbnailVideoDuration
        val lastModifieds = if (getProperLastModified) mediaFetcher.getLastModifieds() else HashMap()
        val dateTakens = if (getProperDateTaken) mediaFetcher.getDateTakens() else HashMap()

        val media = if (showAll) {
            val foldersToScan = mediaFetcher.getFoldersToScan().filter { it != RECYCLE_BIN && it != FAVORITES && !context.config.isFolderProtected(it) }
            val media = ArrayList<Medium>()
            foldersToScan.forEach {
                if (cancelled) {
                    return@forEach
                }
                val newMedia = mediaFetcher.getFilesFrom(
                    it, isPickImage, isPickVideo, getProperDateTaken, getProperLastModified, getProperFileSize,
                    favoritePaths, getVideoDurations, lastModifieds, dateTakens.clone() as HashMap<String, Long>, null
                )
                media.addAll(newMedia)
            }

            mediaFetcher.sortMedia(media, context.config.getFolderSorting(SHOW_ALL))
            media
        } else {
            mediaFetcher.getFilesFrom(
                mPath, isPickImage, isPickVideo, getProperDateTaken, getProperLastModified, getProperFileSize, favoritePaths,
                getVideoDurations, lastModifieds, dateTakens, null
            )
        }

        val filtered = if (favoritesOnly && mPath != FAVORITES) {
            ArrayList(media.filter { it.isFavorite })
        } else {
            media
        }

        return mediaFetcher.groupMedia(filtered, pathToUse)
    }

    fun stopFetching() {
        cancelled = true
        mediaFetcher.shouldStop = true
    }
}
