package tomato.gallery.helpers

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getParentPath
import com.simplemobiletools.commons.extensions.rescanPaths
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import tomato.gallery.extensions.addPathToDB
import java.io.File

@UnstableApi
object VideoTrimHelper {
    /**
     * A running export. [cancel] must be called on the main thread (the Transformer's
     * application thread); it stops the export and removes the partial temp file.
     */
    class TrimJob internal constructor(private val tempFile: File) {
        internal var transformer: Transformer? = null

        @Volatile
        var isCancelled = false
            private set

        fun cancel() {
            if (isCancelled) {
                return
            }
            isCancelled = true
            try {
                transformer?.cancel()
            } catch (_: Exception) {
            }
            transformer = null
            tempFile.delete()
        }
    }

    fun outputPath(sourcePath: String): String {
        val parent = sourcePath.getParentPath()
        val base = File(sourcePath).nameWithoutExtension
        var dest = File(parent, "${base}_trimmed.mp4")
        var n = 2
        while (dest.exists()) {
            dest = File(parent, "${base}_trimmed_$n.mp4")
            n++
        }
        return dest.absolutePath
    }

    /**
     * Starts the export. Must be called on the main thread. The Transformer writes to a
     * hidden sibling temp file, which is renamed to [destPath] only after it completes,
     * so a cancelled or failed export never leaves a partial file under the final name.
     * [callback] runs on the main thread and is not called after [TrimJob.cancel].
     */
    fun trim(
        activity: BaseSimpleActivity,
        sourcePath: String,
        startMs: Long,
        endMs: Long,
        destPath: String,
        callback: (Boolean) -> Unit
    ): TrimJob {
        val dest = File(destPath)
        val temp = File(dest.parentFile, ".${dest.nameWithoutExtension}.trimming.mp4")
        val job = TrimJob(temp)

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs.coerceAtLeast(0L))
            .setEndPositionMs(endMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(File(sourcePath)))
            .setClippingConfiguration(clipping)
            .build()
        val edited = EditedMediaItem.Builder(mediaItem).build()

        val finish: (Boolean) -> Unit = { exported ->
            job.transformer = null
            if (!job.isCancelled) {
                ensureBackgroundThread {
                    val ok = exported && !job.isCancelled && temp.exists() && temp.length() > 0L && moveIntoPlace(temp, dest)
                    if (ok) {
                        activity.rescanPaths(arrayListOf(destPath))
                        activity.addPathToDB(destPath)
                    } else {
                        temp.delete()
                    }
                    activity.runOnUiThread {
                        if (!job.isCancelled) {
                            callback(ok)
                        }
                    }
                }
            }
        }

        try {
            temp.delete()
            val transformer = Transformer.Builder(activity)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finish(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finish(false)
                    }
                })
                .build()
            job.transformer = transformer
            val composition = Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
            ).build()
            transformer.start(composition, temp.absolutePath)
        } catch (e: Exception) {
            finish(false)
        }
        return job
    }

    private fun moveIntoPlace(temp: File, dest: File): Boolean {
        if (dest.exists()) {
            // outputPath() picked a free name; don't clobber a file that appeared since.
            return false
        }
        return temp.renameTo(dest)
    }
}
