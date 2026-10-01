package tomato.gallery.helpers

import android.graphics.Bitmap
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getFileOutputStreamSync
import com.simplemobiletools.commons.extensions.getParentPath
import com.simplemobiletools.commons.extensions.rescanPaths
import tomato.gallery.extensions.addPathToDB
import java.io.File

object MediaFileWriter {
    fun saveJpegNextTo(activity: BaseSimpleActivity, siblingPath: String, suffix: String, bitmap: Bitmap, quality: Int = 90): String? {
        val parent = siblingPath.getParentPath()
        val base = File(siblingPath).nameWithoutExtension
        var dest = File(parent, "${base}_$suffix.jpg")
        var n = 2
        while (dest.exists()) {
            dest = File(parent, "${base}_${suffix}_$n.jpg")
            n++
        }
        return saveJpeg(activity, dest.absolutePath, bitmap, quality)
    }

    fun saveJpeg(activity: BaseSimpleActivity, path: String, bitmap: Bitmap, quality: Int = 90): String? {
        val out = activity.getFileOutputStreamSync(path, "image/jpeg") ?: return null
        return try {
            out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            activity.rescanPaths(arrayListOf(path))
            activity.addPathToDB(path)
            path
        } catch (_: Exception) {
            null
        }
    }
}
