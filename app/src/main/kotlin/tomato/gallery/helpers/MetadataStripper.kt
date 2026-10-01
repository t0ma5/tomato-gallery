package tomato.gallery.helpers

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getDocumentFile
import com.simplemobiletools.commons.extensions.needsStupidWritePermissions
import com.simplemobiletools.commons.extensions.rescanPaths
import tomato.gallery.extensions.AllNonDimensionExifAttributes
import tomato.gallery.extensions.config
import java.io.File

object MetadataStripper {
    private val GPS_TAGS = AllNonDimensionExifAttributes.filter { it.startsWith("GPS") }
    private val XMP_GPS_ATTR = Regex("""\s*exif:GPS[A-Za-z0-9]+="[^"]*"""")
    private val XMP_GPS_ELEM = Regex("""<exif:GPS[^>]*>.*?</exif:GPS[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    internal fun stripGpsFromXmp(xmp: String): String {
        return XMP_GPS_ELEM.replace(XMP_GPS_ATTR.replace(xmp, ""), "")
    }

    fun stripGps(context: Context, path: String): Boolean {
        return mutate(context, path) { exif ->
            GPS_TAGS.forEach { exif.setAttribute(it, null) }
            val xmp = exif.getAttribute(ExifInterface.TAG_XMP)
            if (!xmp.isNullOrEmpty()) {
                exif.setAttribute(ExifInterface.TAG_XMP, stripGpsFromXmp(xmp))
            }
        }
    }

    fun stripAll(context: Context, path: String): Boolean {
        return mutate(context, path) { exif ->
            AllNonDimensionExifAttributes.forEach { exif.setAttribute(it, null) }
        }
    }

    private fun mutate(context: Context, path: String, update: (ExifInterface) -> Unit): Boolean {
        val lastModified = if (!path.startsWith("content://")) File(path).lastModified() else 0L
        val ok = try {
            val contentPath = when {
                path.startsWith("content://") -> path
                context is BaseSimpleActivity && context.needsStupidWritePermissions(path) ->
                    context.getDocumentFile(path)?.uri?.toString()
                else -> null
            }
            if (contentPath != null) {
                context.contentResolver.openFileDescriptor(Uri.parse(contentPath), "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    update(exif)
                    exif.saveAttributes()
                } ?: return false
            } else {
                val file = File(path)
                if (!file.exists() || !file.canWrite()) {
                    return false
                }
                val exif = ExifInterface(file.absolutePath)
                update(exif)
                exif.saveAttributes()
            }
            true
        } catch (_: Exception) {
            false
        }
        if (ok && context is BaseSimpleActivity) {
            context.rescanPaths(arrayListOf(path)) {
                if (lastModified > 0L && context.config.keepLastModified) {
                    File(path).setLastModified(lastModified)
                }
            }
        }
        return ok
    }
}
