package tomato.gallery.activities

import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore.Images
import android.provider.MediaStore.Video
import android.view.WindowManager
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.dialogs.FilePickerDialog
import com.simplemobiletools.commons.extensions.getParentPath
import com.simplemobiletools.commons.extensions.getRealPathFromURI
import com.simplemobiletools.commons.extensions.handleAppPasswordProtection
import com.simplemobiletools.commons.extensions.scanPathRecursively
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.helpers.isPiePlus
import tomato.gallery.R
import tomato.gallery.extensions.addPathToDB
import tomato.gallery.extensions.config
import tomato.gallery.extensions.deleteDBPath
import tomato.gallery.extensions.updateDirectoryPath
import tomato.gallery.helpers.AppLock

open class SimpleActivity : BaseSimpleActivity() {
    private var appLockPromptShown = false

    protected fun ensureAppUnlocked(onUnlocked: () -> Unit): Boolean {
        if (!config.isAppPasswordProtectionOn || AppLock.isUnlocked()) {
            onUnlocked()
            return true
        }
        if (appLockPromptShown) {
            return false
        }
        appLockPromptShown = true
        handleAppPasswordProtection { success ->
            appLockPromptShown = false
            if (success) {
                AppLock.markUnlocked()
                onUnlocked()
            } else {
                finish()
            }
        }
        return false
    }

    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            if (uri != null) {
                val path = getRealPathFromURI(uri)
                if (path != null) {
                    updateDirectoryPath(path.getParentPath())
                    addPathToDB(path)
                }
            }
        }
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher_green,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == tomato.gallery.helpers.REQUEST_SYSTEM_TRASH) {
            val ok = resultCode == RESULT_OK
            val cb = tomato.gallery.extensions.pendingSystemTrashCallback
            val paths = tomato.gallery.extensions.pendingSystemTrashPaths
            tomato.gallery.extensions.pendingSystemTrashCallback = null
            tomato.gallery.extensions.pendingSystemTrashPaths = null
            if (ok && paths != null) {
                ensureBackgroundThread {
                    paths.forEach { deleteDBPath(it) }
                    runOnUiThread { cb?.invoke(true) }
                }
            } else {
                cb?.invoke(false)
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    protected fun checkNotchSupport() {
        if (isPiePlus()) {
            val cutoutMode = when {
                config.showNotch -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else -> WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }

            window.attributes.layoutInDisplayCutoutMode = cutoutMode
            if (config.showNotch) {
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
        }
    }

    protected fun registerFileUpdateListener() {
        try {
            contentResolver.registerContentObserver(Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        } catch (ignored: Exception) {
        }
    }

    protected fun unregisterFileUpdateListener() {
        try {
            contentResolver.unregisterContentObserver(observer)
        } catch (ignored: Exception) {
        }
    }

    protected fun showAddIncludedFolderDialog(callback: () -> Unit) {
        FilePickerDialog(this, config.lastFilepickerPath, false, config.shouldShowHidden, false, true) {
            config.lastFilepickerPath = it
            config.addIncludedFolder(it)
            callback()
            ensureBackgroundThread {
                scanPathRecursively(it)
            }
        }
    }
}
