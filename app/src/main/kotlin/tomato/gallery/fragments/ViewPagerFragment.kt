package tomato.gallery.fragments

import android.graphics.Point
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.view.MotionEvent
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import com.awxkee.jxlcoder.JxlCoder
import com.simplemobiletools.commons.extensions.*
import tomato.gallery.extensions.config
import tomato.gallery.helpers.*
import tomato.gallery.models.Medium
import java.io.File

abstract class ViewPagerFragment : Fragment() {
    var listener: FragmentListener? = null

    abstract fun fullscreenToggled(isFullscreen: Boolean)

    interface FragmentListener {
        fun fragmentClicked()

        fun videoEnded(): Boolean

        fun goToPrevItem()

        fun goToNextItem()

        fun launchViewVideoIntent(path: String)

        fun isSlideShowActive(): Boolean

        fun interruptSlideshowDrag()
    }

    fun getMediumExtendedDetails(medium: Medium): String {
        val file = File(medium.path)
        if (context?.getDoesFilePathExist(file.absolutePath) == false) {
            return ""
        }

        val path = "${file.parent.trimEnd('/')}/"
        val exif = try {
            ExifInterface(medium.path)
        } catch (e: Exception) {
            return ""
        }

        val details = StringBuilder()
        val detailsFlag = context!!.config.extendedDetails
        if (detailsFlag and EXT_NAME != 0) {
            medium.name.let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_PATH != 0) {
            path.let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_SIZE != 0) {
            file.length().formatSize().let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_RESOLUTION != 0) {
            getResolution(medium, file)?.let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_LAST_MODIFIED != 0) {
            getFileLastModified(file).let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_DATE_TAKEN != 0) {
            exif.getExifDateTaken(context!!).let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_CAMERA_MODEL != 0) {
            exif.getExifCameraModel().let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_EXIF_PROPERTIES != 0) {
            exif.getExifProperties().let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_GPS != 0) {
            getLatLonAltitude(medium.path).let { if (it.isNotEmpty()) details.appendLine(it) }
        }
        return details.toString().trim()
    }

    fun getPathToLoad(medium: Medium) = if (context?.isPathOnOTG(medium.path) == true) medium.path.getOTGPublicPath(context!!) else medium.path

    private fun getResolution(medium: Medium, file: File): String? {
        if (medium.name.isJxl()) {
            val resolution = try {
                JxlCoder.getSize(file.readBytes())
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
            return resolution?.let { Point(it.width, it.height).formatAsResolution() }
        }

        return context?.getResolution(file.absolutePath)?.formatAsResolution()
    }

    private fun getFileLastModified(file: File): String {
        val projection = arrayOf(Images.Media.DATE_MODIFIED)
        val uri = Files.getContentUri("external")
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        val cursor = context!!.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            return if (cursor.moveToFirst()) {
                val dateModified = cursor.getLongValue(Images.Media.DATE_MODIFIED) * 1000L
                dateModified.formatDate(context!!)
            } else {
                file.lastModified().formatDate(context!!)
            }
        }
        return ""
    }

    private fun getLatLonAltitude(path: String): String {
        var result = ""
        val exif = try {
            ExifInterface(path)
        } catch (e: Exception) {
            return ""
        }

        val latLon = FloatArray(2)

        if (exif.getLatLong(latLon)) {
            result = "${latLon[0]},  ${latLon[1]}"
        }

        val altitude = exif.getAltitude(0.0)
        if (altitude != 0.0) {
            result += ",  ${altitude}m"
        }

        return result.trimStart(',').trim()
    }

    protected fun handleEvent(event: MotionEvent): Boolean {
        if (listener?.isSlideShowActive() == true) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                listener?.interruptSlideshowDrag()
            }
            return false
        }
        if (context?.config?.allowDownGesture != true) return false
        val host = activity as? ViewerMotionHost ?: return false
        return host.onViewerTouch(event)
    }
}
