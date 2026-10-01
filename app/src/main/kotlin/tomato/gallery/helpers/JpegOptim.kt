package tomato.gallery.helpers

import android.net.Uri
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getFileInputStreamSync
import com.simplemobiletools.commons.extensions.getFileOutputStreamSync
import com.simplemobiletools.commons.extensions.getMimeType
import com.simplemobiletools.commons.extensions.isJpg
import com.simplemobiletools.commons.extensions.needsStupidWritePermissions
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class JpegOptimResult(val ok: Boolean, val bytesSaved: Long, val skipped: Boolean = false)

object JpegOptim {
    val isAvailable: Boolean = runCatching { System.loadLibrary("jpegoptim-jni") }.isSuccess

    @JvmStatic
    private external fun optimizeNative(path: String, tmpDir: String, quality: Int): Long

    fun isSupported(path: String) = isAvailable && path.isJpg()

    fun optimize(activity: BaseSimpleActivity, path: String, quality: Int): JpegOptimResult {
        if (!isSupported(path)) {
            return JpegOptimResult(false, 0)
        }

        val workDir = File(activity.cacheDir, "jpegoptim/${Thread.currentThread().id}").apply { mkdirs() }
        val work = File(workDir, "work.jpg")
        val orig = File(workDir, "orig.jpg")
        val tmpDir = workDir.absolutePath + File.separator
        try {
            copySource(activity, path, work)
            work.copyTo(orig, overwrite = true)
            val before = work.length()
            if (before <= 0L) {
                return JpegOptimResult(false, 0)
            }
            if (isHdrOrContainerJpeg(work)) {
                return JpegOptimResult(ok = true, bytesSaved = 0, skipped = true)
            }

            val saved = optimizeNative(work.absolutePath, tmpDir, quality.coerceAtLeast(-1).coerceAtMost(100))
            if (saved < 0L) {
                return JpegOptimResult(false, 0)
            }
            if (saved == 0L || work.length() >= before) {
                return JpegOptimResult(true, 0)
            }
            if (!writeBack(activity, path, work, orig)) {
                return JpegOptimResult(false, 0)
            }
            return JpegOptimResult(true, saved)
        } catch (_: Exception) {
            return JpegOptimResult(false, 0)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun copySource(activity: BaseSimpleActivity, path: String, dest: File) {
        if (path.startsWith("content://")) {
            activity.contentResolver.openInputStream(Uri.parse(path)).use { input ->
                requireNotNull(input)
                dest.outputStream().use { input.copyTo(it) }
            }
        } else {
            activity.getFileInputStreamSync(path)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: error("cannot read $path")
        }
    }

    private fun writeBack(activity: BaseSimpleActivity, path: String, source: File, orig: File): Boolean {
        if (!path.startsWith("content://") && !activity.needsStupidWritePermissions(path)) {
            return writePlainAtomic(path, source)
        }

        val mime = path.getMimeType().ifEmpty { "image/jpeg" }
        return try {
            val out = activity.getFileOutputStreamSync(path, mime) ?: return restoreOrig(activity, path, orig, mime)
            source.inputStream().use { input ->
                out.use { input.copyTo(it) }
            }
            val expected = source.length()
            val actual = if (path.startsWith("content://")) {
                expected
            } else {
                File(path).length()
            }
            if (actual > 0L && actual != expected) {
                restoreOrig(activity, path, orig, mime)
                return false
            }
            true
        } catch (_: Exception) {
            restoreOrig(activity, path, orig, mime)
            false
        }
    }

    private fun writePlainAtomic(path: String, source: File): Boolean {
        val dest = File(path)
        val parent = dest.parentFile ?: return false
        val tmp = File(parent, ".${dest.name}.jpegoptim.tmp")
        return try {
            FileInputStream(source).use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                if (dest.exists() && !dest.delete()) {
                    tmp.delete()
                    return false
                }
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    return false
                }
            }
            true
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    private fun restoreOrig(activity: BaseSimpleActivity, path: String, orig: File, mime: String): Boolean {
        return try {
            if (!orig.exists() || orig.length() <= 0L) {
                return false
            }
            val out = activity.getFileOutputStreamSync(path, mime) ?: return false
            orig.inputStream().use { input ->
                out.use { input.copyTo(it) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun isHdrOrContainerJpeg(file: File): Boolean {
        return try {
            val size = file.length().coerceAtMost(256 * 1024L).toInt()
            if (size <= 0) {
                return false
            }
            val probe = ByteArray(size)
            FileInputStream(file).use { input ->
                var read = 0
                while (read < size) {
                    val n = input.read(probe, read, size - read)
                    if (n <= 0) break
                    read += n
                }
            }
            val text = String(probe, Charsets.ISO_8859_1)
            text.contains("MPF\u0000") || text.contains("hdrgm:") || text.contains("Container:Directory")
        } catch (_: Exception) {
            false
        }
    }
}
