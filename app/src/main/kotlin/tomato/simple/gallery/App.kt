package tomato.simple.gallery

import android.app.Application
import android.os.Build
import com.github.ajalt.reprint.core.Reprint
import com.simplemobiletools.commons.extensions.checkUseEnglish
import com.squareup.picasso.Downloader
import com.squareup.picasso.Picasso
import okhttp3.Request
import okhttp3.Response
import tomato.simple.gallery.extensions.migrateDarkBackgroundColor
import tomato.simple.gallery.extensions.migrateLegacyRecycleBin

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (isOcrProcess()) {
            return
        }
        migrateLegacyRecycleBin()
        migrateDarkBackgroundColor()
        checkUseEnglish()
        Reprint.initialize(this)
        Picasso.setSingletonInstance(Picasso.Builder(this).downloader(object : Downloader {
            override fun load(request: Request) = Response.Builder().build()

            override fun shutdown() {}
        }).build())
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
