package tomato.gallery.fragments

import android.content.Context
import android.graphics.Point
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.view.MotionEvent
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import com.awxkee.jxlcoder.JxlCoder
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import tomato.gallery.extensions.config
import tomato.gallery.helpers.*
import tomato.gallery.models.Medium
import java.io.File

abstract class ViewPagerFragment : Fragment() {
    var listener: FragmentListener? = null

    abstract fun fullscreenToggled(isFullscreen: Boolean)

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // after a recreation the host never sets the listener on restored fragments
        if (listener == null) {
            listener = activity as? FragmentListener
        }
    }

    /** Builds the extended details off the main thread and delivers them while the view is still alive. */
    protected fun loadExtendedDetails(medium: Medium, onReady: (String) -> Unit) {
        ensureBackgroundThread {
            val details = try {
                getMediumExtendedDetails(medium)
            } catch (_: Exception) {
                ""
            } catch (_: OutOfMemoryError) {
                ""
            }
            activity?.runOnUiThread {
                if (isAdded && view != null) {
                    onReady(details)
                }
            }
        }
    }

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
        val ctx = context ?: return ""
        val file = File(medium.path)
        if (!ctx.getDoesFilePathExist(file.absolutePath)) {
            return ""
        }

        val path = "${(file.parent ?: "").trimEnd('/')}/"
        val exif = try {
            ExifInterface(medium.path)
        } catch (e: Exception) {
            return ""
        }

        val details = StringBuilder()
        val detailsFlag = ctx.config.extendedDetails
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
            getResolution(ctx, medium, file)?.let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_LAST_MODIFIED != 0) {
            getFileLastModified(ctx, file).let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_DATE_TAKEN != 0) {
            exif.getExifDateTaken(ctx).let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_CAMERA_MODEL != 0) {
            exif.getExifCameraModel().let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_EXIF_PROPERTIES != 0) {
            exif.getExifProperties().let { if (it.isNotEmpty()) details.appendLine(it) }
        }

        if (detailsFlag and EXT_GPS != 0) {
            getLatLonAltitude(exif).let { if (it.isNotEmpty()) details.appendLine(it) }
        }
        return details.toString().trim()
    }

    fun getPathToLoad(medium: Medium) = if (context?.isPathOnOTG(medium.path) == true) medium.path.getOTGPublicPath(context!!) else medium.path

    private fun getResolution(ctx: Context, medium: Medium, file: File): String? {
        if (medium.name.isJxl()) {
            return getJxlSize(file)?.formatAsResolution()
        }

        return ctx.getResolution(file.absolutePath)?.formatAsResolution()
    }

    // The JXL size lives in the header, so try the file head first and only read everything if that is not enough.
    private fun getJxlSize(file: File): Point? {
        val head = try {
            file.inputStream().use { input ->
                val buf = ByteArray(JXL_HEADER_PROBE)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                if (total == buf.size) buf else buf.copyOf(total)
            }
        } catch (_: Exception) {
            return null
        }

        fun sizeOf(bytes: ByteArray) = try {
            JxlCoder.getSize(bytes)?.let { Point(it.width, it.height) }?.takeIf { it.x > 0 && it.y > 0 }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }

        return sizeOf(head) ?: if (head.size < JXL_HEADER_PROBE) {
            null
        } else {
            try {
                sizeOf(file.readBytes())
            } catch (_: OutOfMemoryError) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getFileLastModified(ctx: Context, file: File): String {
        val projection = arrayOf(Images.Media.DATE_MODIFIED)
        val uri = Files.getContentUri("external")
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)
        val cursor = ctx.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            return if (cursor.moveToFirst()) {
                val dateModified = cursor.getLongValue(Images.Media.DATE_MODIFIED) * 1000L
                dateModified.formatDate(ctx)
            } else {
                file.lastModified().formatDate(ctx)
            }
        }
        return ""
    }

    private fun getLatLonAltitude(exif: ExifInterface): String {
        var result = ""
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

    private companion object {
        const val JXL_HEADER_PROBE = 64 * 1024
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
