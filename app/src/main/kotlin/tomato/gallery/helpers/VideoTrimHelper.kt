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
import tomato.gallery.extensions.addPathToDB
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
object VideoTrimHelper {
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

    fun trim(
        activity: BaseSimpleActivity,
        sourcePath: String,
        startMs: Long,
        endMs: Long,
        destPath: String,
        callback: (Boolean) -> Unit
    ) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs.coerceAtLeast(0L))
            .setEndPositionMs(endMs)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(File(sourcePath)))
            .setClippingConfiguration(clipping)
            .build()
        val edited = EditedMediaItem.Builder(mediaItem).build()
        val error = AtomicReference<Exception?>(null)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            val transformer = Transformer.Builder(activity)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        latch.countDown()
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error.set(exportException)
                        latch.countDown()
                    }
                })
                .build()
            val composition = Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(listOf(edited))
            ).build()
            transformer.start(composition, destPath)
        }
        Thread {
            val finished = latch.await(10, TimeUnit.MINUTES)
            val ok = finished && error.get() == null && File(destPath).exists() && File(destPath).length() > 0L
            if (ok) {
                activity.rescanPaths(arrayListOf(destPath))
                activity.addPathToDB(destPath)
            } else {
                File(destPath).delete()
            }
            activity.runOnUiThread { callback(ok) }
        }.start()
    }
}
