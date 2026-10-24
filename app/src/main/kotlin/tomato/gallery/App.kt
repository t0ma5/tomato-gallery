package tomato.gallery

import android.app.Application
import android.os.Build
import com.github.ajalt.reprint.core.Reprint
import com.simplemobiletools.commons.extensions.checkUseEnglish
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response
import tomato.gallery.extensions.migrateDarkBackgroundColor
import tomato.gallery.extensions.migrateLegacyRecycleBin
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isOcrProcess()) {
            return
        }
        migrateLegacyRecycleBin()
        migrateDarkBackgroundColor()
        checkUseEnglish()
        clearStaleMotionPhotoCache()
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
    }

    // Extracted motion-photo clips are only needed while a viewer is open
    private fun clearStaleMotionPhotoCache() {
        ensureBackgroundThread {
            try {
                File(cacheDir, "motion_photos").deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    private fun isOcrProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            return false
        }
        return name.endsWith(":ocr")
    }
}
