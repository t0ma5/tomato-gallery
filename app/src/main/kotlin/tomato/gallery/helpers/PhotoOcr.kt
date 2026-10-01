package tomato.gallery.helpers

import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.equationl.ncnnandroidppocr.OCR
import com.equationl.ncnnandroidppocr.bean.Device
import com.equationl.ncnnandroidppocr.bean.DrawModel
import com.equationl.ncnnandroidppocr.bean.ImageSize
import com.equationl.ncnnandroidppocr.bean.ModelType
import com.simplemobiletools.commons.extensions.getImageResolution
import kotlin.math.max
import kotlin.math.roundToInt

sealed class PhotoOcrResult {
    data class Text(val text: String) : PhotoOcrResult()
    data object Empty : PhotoOcrResult()
    data object Failed : PhotoOcrResult()
}

object PhotoOcr {
    private const val MAX_SIDE = 1600

    private val lock = Any()
    private var engine: OCR? = null
    private var unavailable = false
    private var nativeReady = false

    @JvmStatic
    private external fun disableOcrParallel()

    fun recognize(context: Context, path: String): PhotoOcrResult {
        if (path.isEmpty()) {
            return PhotoOcrResult.Failed
        }
        val bitmap = decode(context, path) ?: return PhotoOcrResult.Failed
        return try {
            val ocr = engine(context) ?: return PhotoOcrResult.Failed
            val text = synchronized(lock) {
                keepOcrSerial()
                ocr.detectBitmap(bitmap, DrawModel.None)?.text?.trim().orEmpty()
            }
            if (text.isEmpty()) PhotoOcrResult.Empty else PhotoOcrResult.Text(text)
        } catch (_: UnsatisfiedLinkError) {
            PhotoOcrResult.Failed
        } catch (_: Exception) {
            PhotoOcrResult.Failed
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun engine(context: Context): OCR? {
        synchronized(lock) {
            engine?.let { return it }
            if (unavailable) {
                return null
            }
            return try {
                prepareNative()
                val ocr = OCR()
                val loaded = ocr.initModelFromAssert(
                    context.applicationContext.assets,
                    ModelType.Mobile,
                    ImageSize.Size720,
                    Device.CPU
                )
                if (!loaded) {
                    unavailable = true
                    null
                } else {
                    engine = ocr
                    ocr
                }
            } catch (_: UnsatisfiedLinkError) {
                unavailable = true
                null
            } catch (_: Exception) {
                unavailable = true
                null
            }
        }
    }

    private fun prepareNative() {
        if (nativeReady) {
            return
        }
        runCatching {
            Os.setenv("KMP_AFFINITY", "disabled", true)
            Os.setenv("OMP_PROC_BIND", "false", true)
            Os.setenv("OMP_NUM_THREADS", "1", true)
        }
        System.loadLibrary("ocr-threads")
        nativeReady = true
    }

    private fun keepOcrSerial() {
        prepareNative()
        runCatching { disableOcrParallel() }
    }

    private fun decode(context: Context, path: String): Bitmap? {
        val options = RequestOptions()
            .disallowHardwareConfig()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
        val resolution = runCatching { path.getImageResolution(context) }.getOrNull()
        val request = Glide.with(context).asBitmap().load(path).apply(options)
        val target = if (resolution != null && max(resolution.x, resolution.y) > MAX_SIDE) {
            val (width, height) = fitInside(resolution.x, resolution.y, MAX_SIDE)
            request.submit(width, height)
        } else if (resolution == null) {
            request.submit(MAX_SIDE, MAX_SIDE)
        } else {
            request.submit()
        }
        return try {
            target.get().copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Glide.with(context).clear(target) }
        }
    }

    private fun fitInside(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longSide = max(width, height).coerceAtLeast(1)
        val scale = maxSide.toFloat() / longSide
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }
}
