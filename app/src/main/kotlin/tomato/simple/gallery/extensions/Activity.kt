package tomato.simple.gallery.extensions

import android.annotation.TargetApi
import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Files
import android.provider.MediaStore.Images
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.dialogs.SecurityDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.commons.models.FileDirItem
import tomato.simple.gallery.BuildConfig
import tomato.simple.gallery.R
import tomato.simple.gallery.activities.MediaActivity
import tomato.simple.gallery.activities.SettingsActivity
import tomato.simple.gallery.activities.SimpleActivity
import tomato.simple.gallery.dialogs.AllFilesPermissionDialog
import tomato.simple.gallery.dialogs.PickDirectoryDialog
import tomato.simple.gallery.dialogs.ResizeMultipleImagesDialog
import tomato.simple.gallery.dialogs.ResizeWithPathDialog
import tomato.simple.gallery.helpers.DIRECTORY
import tomato.simple.gallery.helpers.JpegQuality
import tomato.simple.gallery.helpers.JpegTransform
import tomato.simple.gallery.helpers.MetadataStripper
import tomato.simple.gallery.helpers.RECYCLE_BIN
import tomato.simple.gallery.helpers.REQUEST_FAVORITE_SYSTEM
import tomato.simple.gallery.helpers.REQUEST_SYSTEM_TRASH
import tomato.simple.gallery.models.DateTaken
import tomato.simple.gallery.models.Medium
import com.squareup.picasso.Picasso
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

fun Activity.sharePath(path: String) {
    sharePathIntent(path, BuildConfig.APPLICATION_ID)
}

fun Activity.sharePaths(paths: ArrayList<String>) {
    sharePathsIntent(paths, BuildConfig.APPLICATION_ID)
}

fun applyFavoriteToOpenGrids(path: String, isFavorite: Boolean) {
    MediaActivity.mMedia.filterIsInstance<Medium>().forEach {
        if (it.path.equals(path, true)) {
            it.isFavorite = isFavorite
        }
    }
}

fun Activity.updateFavorite(path: String, isFavorite: Boolean) {
    if (!updateFavoriteLocal(path, isFavorite)) {
        return
    }
    applyFavoriteToOpenGrids(path, isFavorite)
    if (!isRPlus()) {
        return
    }
    try {
        val uri = getFilePublicUri(java.io.File(path), BuildConfig.APPLICATION_ID)
        if (isMediaStoreUri(uri) && isSupportedForFavorite(contentResolver, uri)) {
            updateFavoriteInMediaStore(uri, isFavorite)
        }
    } catch (_: Exception) {
    }
}

private fun isMediaStoreUri(uri: Uri): Boolean {
    return uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith("media") == true
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
private fun Activity.updateFavoriteInMediaStore(uri: Uri, isFavorite: Boolean) {
    try {
        if (checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            val pendingIntent = MediaStore.createFavoriteRequest(contentResolver, listOf(uri), isFavorite)
            startIntentSenderForResult(pendingIntent.intentSender, REQUEST_FAVORITE_SYSTEM, null, 0, 0, 0)
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_FAVORITE, if (isFavorite) 1 else 0)
            }
            contentResolver.update(uri, values, null, null)
        }
    } catch (_: Exception) {
    }
}

private fun isSupportedForFavorite(contentResolver: ContentResolver, uri: Uri): Boolean {
    return try {
        val type = contentResolver.getType(uri) ?: return false
        type == "image/jpeg" || type.startsWith("video/")
    } catch (_: Exception) {
        false
    }
}

fun BaseSimpleActivity.shouldUseSystemTrash() = isRPlus() && config.useSystemTrash

fun BaseSimpleActivity.trashPathsWithSystem(paths: ArrayList<String>, callback: ((Boolean) -> Unit)?) {
    if (!isRPlus()) {
        callback?.invoke(false)
        return
    }
    val uris = paths.mapNotNull { path ->
        try {
            getFilePublicUri(java.io.File(path), BuildConfig.APPLICATION_ID)
        } catch (_: Exception) {
            null
        }
    }
    if (uris.isEmpty()) {
        callback?.invoke(false)
        return
    }
    pendingSystemTrashCallback = callback
    pendingSystemTrashPaths = ArrayList(paths)
    try {
        val pendingIntent = MediaStore.createTrashRequest(contentResolver, uris, true)
        startIntentSenderForResult(pendingIntent.intentSender, REQUEST_SYSTEM_TRASH, null, 0, 0, 0)
    } catch (_: Exception) {
        pendingSystemTrashCallback = null
        pendingSystemTrashPaths = null
        callback?.invoke(false)
    }
}

internal var pendingSystemTrashCallback: ((Boolean) -> Unit)? = null
internal var pendingSystemTrashPaths: ArrayList<String>? = null

fun Activity.shareMediumPath(path: String) {
    val activity = this
    if (activity is BaseSimpleActivity && activity.config.stripMetadataOnShare) {
        ensureBackgroundThread {
            try {
                val dest = File(activity.cacheDir, "share/${File(path).name}")
                dest.parentFile?.mkdirs()
                File(path).copyTo(dest, overwrite = true)
                MetadataStripper.stripAll(activity, dest.absolutePath)
                activity.runOnUiThread { sharePath(dest.absolutePath) }
            } catch (_: Exception) {
                activity.runOnUiThread { sharePath(path) }
            }
        }
        return
    }
    sharePath(path)
}

fun Activity.shareMediaPaths(paths: ArrayList<String>) {
    sharePaths(paths)
}

fun Activity.setAs(path: String) {
    setAsIntent(path, BuildConfig.APPLICATION_ID)
}

fun Activity.openPath(path: String, forceChooser: Boolean, extras: HashMap<String, Boolean> = HashMap()) {
    openPathIntent(path, forceChooser, BuildConfig.APPLICATION_ID, extras = extras)
}

fun Activity.launchGesturePlayer(path: String, extras: HashMap<String, Boolean> = HashMap()) {
    ensureBackgroundThread {
        val newUri = getFinalUriFromPath(path, BuildConfig.APPLICATION_ID)
        if (newUri == null) {
            toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
            return@ensureBackgroundThread
        }

        val mimeType = getUriMimeType(path, newUri)
        runOnUiThread {
            Intent(applicationContext, tomato.simple.gallery.activities.VideoPlayerActivity::class.java).apply {
                setDataAndType(newUri, mimeType)
                for ((key, value) in extras) putExtra(key, value)
                startActivity(this)
            }
        }
    }
}

fun Activity.openEditor(path: String, forceChooser: Boolean = false) {
    val newPath = path.removePrefix("file://")
    if (newPath.isEmpty()) {
        toast(R.string.invalid_image_path)
        return
    }

    // "Edit with…" still goes through Commons FileProvider. The in-app editor
    // must not: Commons also FileProvider-wraps a sibling `_1` output path that
    // does not exist yet, which toasts IllegalArgumentException on the way in.
    if (forceChooser) {
        openEditorIntent(newPath, true, BuildConfig.APPLICATION_ID)
        return
    }

    val uri = when {
        newPath.startsWith("content:", true) -> Uri.parse(newPath)
        newPath.startsWith("file:", true) -> Uri.parse(newPath)
        else -> Uri.fromFile(java.io.File(newPath))
    }
    val mime = contentResolver.getType(uri) ?: newPath.getMimeType().ifEmpty { "image/*" }
    try {
        startActivityForResult(Intent(this, tomato.simple.gallery.activities.EditActivity::class.java).apply {
            action = Intent.ACTION_EDIT
            setDataAndType(uri, mime)
            putExtra(REAL_FILE_PATH, newPath)
        }, REQUEST_EDIT_IMAGE)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun Activity.launchCamera() {
    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    launchActivityIntent(intent)
}

fun SimpleActivity.launchSettings() {
    hideKeyboard()
    startActivity(Intent(applicationContext, SettingsActivity::class.java))
}

fun SimpleActivity.launchAbout() {
    val licenses = LICENSE_GLIDE or LICENSE_CROPPER or LICENSE_RTL or LICENSE_SUBSAMPLING or LICENSE_PATTERN or LICENSE_REPRINT or LICENSE_GIF_DRAWABLE or
        LICENSE_PICASSO or LICENSE_EXOPLAYER or LICENSE_PANORAMA_VIEW or LICENSE_SANSELAN or LICENSE_FILTERS or LICENSE_GESTURE_VIEWS or LICENSE_APNG or
        LICENSE_JPEGOPTIM or LICENSE_PADDLEOCR

    val faqItems = arrayListOf(
        FAQItem(R.string.faq_3_title, R.string.faq_3_text),
        FAQItem(R.string.faq_12_title, R.string.faq_12_text),
        FAQItem(R.string.faq_7_title, R.string.faq_7_text),
        FAQItem(R.string.faq_14_title, R.string.faq_14_text),
        FAQItem(R.string.faq_1_title, R.string.faq_1_text),
        FAQItem(R.string.faq_5_title, R.string.faq_5_text),
        FAQItem(R.string.faq_4_title, R.string.faq_4_text),
        FAQItem(R.string.faq_6_title, R.string.faq_6_text),
        FAQItem(R.string.faq_8_title, R.string.faq_8_text),
        FAQItem(R.string.faq_10_title, R.string.faq_10_text),
        FAQItem(R.string.faq_11_title, R.string.faq_11_text),
        FAQItem(R.string.faq_13_title, R.string.faq_13_text),
        FAQItem(R.string.faq_15_title, R.string.faq_15_text),
        FAQItem(R.string.faq_2_title, R.string.faq_2_text),
        FAQItem(R.string.faq_18_title, R.string.faq_18_text),
        FAQItem(R.string.faq_19_title, R.string.faq_19_text),
        FAQItem(com.simplemobiletools.commons.R.string.faq_9_title_commons, com.simplemobiletools.commons.R.string.faq_9_text_commons),
    )

    if (!resources.getBoolean(com.simplemobiletools.commons.R.bool.hide_google_relations)) {
        faqItems.add(FAQItem(com.simplemobiletools.commons.R.string.faq_2_title_commons, com.simplemobiletools.commons.R.string.faq_2_text_commons))
        faqItems.add(FAQItem(com.simplemobiletools.commons.R.string.faq_6_title_commons, com.simplemobiletools.commons.R.string.faq_6_text_commons))
        faqItems.add(FAQItem(com.simplemobiletools.commons.R.string.faq_7_title_commons, com.simplemobiletools.commons.R.string.faq_7_text_commons))
        faqItems.add(FAQItem(com.simplemobiletools.commons.R.string.faq_10_title_commons, com.simplemobiletools.commons.R.string.faq_10_text_commons))
    }

    if (isRPlus() && !isExternalStorageManager()) {
        faqItems.add(0, FAQItem(R.string.faq_16_title, "${getString(R.string.faq_16_text)} ${getString(R.string.faq_16_text_extra)}"))
        faqItems.add(1, FAQItem(R.string.faq_17_title, R.string.faq_17_text))
        faqItems.removeIf { it.text == R.string.faq_7_text }
        faqItems.removeIf { it.text == R.string.faq_14_text }
        faqItems.removeIf { it.text == R.string.faq_8_text }
    }

    startAboutActivity(R.string.app_name, licenses, "${BuildConfig.VERSION_NAME} (${aboutMonthYear()})", faqItems, true)
}

private fun aboutMonthYear(): String {
    val calendar = java.util.Calendar.getInstance()
    val month = java.text.DateFormatSymbols(java.util.Locale.US).months[calendar.get(java.util.Calendar.MONTH)]
    return "$month ${calendar.get(java.util.Calendar.YEAR)}"
}

fun BaseSimpleActivity.handleMediaManagementPrompt(callback: () -> Unit) {
    if (canManageMedia() || isExternalStorageManager()) {
        callback()
    } else if (isRPlus() && resources.getBoolean(R.bool.require_all_files_access) && !config.avoidShowingAllFilesPrompt) {
        if (Environment.isExternalStorageManager()) {
            callback()
        } else {
            var messagePrompt = getString(com.simplemobiletools.commons.R.string.access_storage_prompt)
            messagePrompt += if (isSPlus()) {
                "\n\n${getString(R.string.media_management_alternative)}"
            } else {
                "\n\n${getString(R.string.alternative_media_access)}"
            }

            AllFilesPermissionDialog(this, messagePrompt, callback = { success ->
                if (success) {
                    launchGrantAllFilesIntent()
                }
            }, neutralPressed = {
                if (isSPlus()) {
                    launchMediaManagementIntent(callback)
                } else {
                    config.avoidShowingAllFilesPrompt = true
                }
            })
        }
    } else {
        callback()
    }
}

fun BaseSimpleActivity.launchGrantAllFilesIntent() {
    try {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.addCategory("android.intent.category.DEFAULT")
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent()
        intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }
}

fun AppCompatActivity.showSystemUI(toggleActionBarVisibility: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        show(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

fun AppCompatActivity.hideSystemUI(toggleActionBarVisibility: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

fun Window.applyMaxBrightness(enable: Boolean, original: Float?): Float? {
    val attrs = attributes
    return if (enable) {
        val saved = original ?: attrs.screenBrightness
        attrs.screenBrightness = 1f
        attributes = attrs
        saved
    } else {
        if (original != null) {
            attrs.screenBrightness = original
            attributes = attrs
        }
        original
    }
}

fun BaseSimpleActivity.addNoMedia(path: String, callback: () -> Unit) {
    val file = File(path, NOMEDIA)
    if (getDoesFilePathExist(file.absolutePath)) {
        callback()
        return
    }

    if (needsStupidWritePermissions(path)) {
        handleSAFDialog(file.absolutePath) {
            if (!it) {
                return@handleSAFDialog
            }

            val fileDocument = getDocumentFile(path)
            if (fileDocument?.exists() == true && fileDocument.isDirectory) {
                fileDocument.createFile("", NOMEDIA)
                addNoMediaIntoMediaStore(file.absolutePath)
                callback()
            } else {
                toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                callback()
            }
        }
    } else {
        try {
            if (file.createNewFile()) {
                ensureBackgroundThread {
                    addNoMediaIntoMediaStore(file.absolutePath)
                }
            } else {
                toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
        callback()
    }
}

fun BaseSimpleActivity.addNoMediaIntoMediaStore(path: String) {
    try {
        val content = ContentValues().apply {
            put(Files.FileColumns.TITLE, NOMEDIA)
            put(Files.FileColumns.DATA, path)
            put(Files.FileColumns.MEDIA_TYPE, Files.FileColumns.MEDIA_TYPE_NONE)
        }
        contentResolver.insert(Files.getContentUri("external"), content)
    } catch (e: Exception) {
        showErrorToast(e)
    }
}

fun BaseSimpleActivity.removeNoMedia(path: String, callback: (() -> Unit)? = null) {
    val file = File(path, NOMEDIA)
    if (!getDoesFilePathExist(file.absolutePath)) {
        callback?.invoke()
        return
    }

    tryDeleteFileDirItem(file.toFileDirItem(applicationContext), false, false) {
        callback?.invoke()
        deleteFromMediaStore(file.absolutePath) { needsRescan ->
            if (needsRescan) {
                rescanAndDeletePath(path) {
                    rescanFolderMedia(path)
                }
            } else {
                rescanFolderMedia(path)
            }
        }
    }
}

fun BaseSimpleActivity.toggleFileVisibility(oldPath: String, hide: Boolean, callback: ((newPath: String) -> Unit)? = null) {
    val path = oldPath.getParentPath()
    var filename = oldPath.getFilenameFromPath()
    if ((hide && filename.startsWith('.')) || (!hide && !filename.startsWith('.'))) {
        callback?.invoke(oldPath)
        return
    }

    filename = if (hide) {
        ".${filename.trimStart('.')}"
    } else {
        filename.substring(1, filename.length)
    }

    val newPath = "$path/$filename"
    renameFile(oldPath, newPath, false) { success, useAndroid30Way ->
        runOnUiThread {
            callback?.invoke(newPath)
        }

        ensureBackgroundThread {
            updateDBMediaPath(oldPath, newPath)
        }
    }
}

fun BaseSimpleActivity.tryCopyMoveFilesTo(fileDirItems: ArrayList<FileDirItem>, isCopyOperation: Boolean, callback: (destinationPath: String) -> Unit) {
    if (fileDirItems.isEmpty()) {
        toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
        return
    }

    val source = fileDirItems[0].getParentPath()
    PickDirectoryDialog(this, source, true, false, true, false) {
        val destination = it
        handleSAFDialog(source) {
            if (it) {
                copyMoveFilesTo(fileDirItems, source.trimEnd('/'), destination, isCopyOperation, true, config.shouldShowHidden, callback)
            }
        }
    }
}

fun BaseSimpleActivity.tryDeleteFileDirItem(
    fileDirItem: FileDirItem, allowDeleteFolder: Boolean = false, deleteFromDatabase: Boolean,
    callback: ((wasSuccess: Boolean) -> Unit)? = null
) {
    deleteFile(fileDirItem, allowDeleteFolder, isDeletingMultipleFiles = false) {
        if (deleteFromDatabase) {
            ensureBackgroundThread {
                deleteDBPath(fileDirItem.path)
                runOnUiThread {
                    callback?.invoke(it)
                }
            }
        } else {
            callback?.invoke(it)
        }
    }
}

fun BaseSimpleActivity.movePathsInRecycleBin(paths: ArrayList<String>, callback: ((wasSuccess: Boolean) -> Unit)?) {
    if (shouldUseSystemTrash()) {
        trashPathsWithSystem(paths, callback)
        return
    }
    ensureBackgroundThread {
        var pathsCnt = paths.size
        val OTGPath = config.OTGPath

        for (source in paths) {
            if (OTGPath.isNotEmpty() && source.startsWith(OTGPath)) {
                var inputStream: InputStream? = null
                var out: OutputStream? = null
                try {
                    val destination = "$recycleBinPath/$source"
                    val fileDocument = getSomeDocumentFile(source)
                    inputStream = applicationContext.contentResolver.openInputStream(fileDocument?.uri!!)
                    out = getFileOutputStreamSync(destination, source.getMimeType())

                    var copiedSize = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var bytes = inputStream!!.read(buffer)
                    while (bytes >= 0) {
                        out!!.write(buffer, 0, bytes)
                        copiedSize += bytes
                        bytes = inputStream.read(buffer)
                    }

                    out?.flush()

                    if (fileDocument.getItemSize(true) == copiedSize && getDoesFilePathExist(destination)) {
                        mediaDB.updateDeleted("$RECYCLE_BIN$source", System.currentTimeMillis(), source)
                        pathsCnt--
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                    return@ensureBackgroundThread
                } finally {
                    inputStream?.close()
                    out?.close()
                }
            } else {
                val file = File(source)
                val internalFile = File(recycleBinPath, source)
                val lastModified = file.lastModified()
                try {
                    if (file.copyRecursively(internalFile, true)) {
                        mediaDB.updateDeleted("$RECYCLE_BIN$source", System.currentTimeMillis(), source)
                        pathsCnt--

                        if (config.keepLastModified && lastModified != 0L) {
                            internalFile.setLastModified(lastModified)
                        }
                    }
                } catch (e: Exception) {
                    showErrorToast(e)
                    return@ensureBackgroundThread
                }
            }
        }
        callback?.invoke(pathsCnt == 0)
    }
}

fun BaseSimpleActivity.restoreRecycleBinPath(path: String, callback: () -> Unit) {
    restoreRecycleBinPaths(arrayListOf(path), callback)
}

fun BaseSimpleActivity.restoreRecycleBinPaths(paths: ArrayList<String>, callback: () -> Unit) {
    ensureBackgroundThread {
        val newPaths = ArrayList<String>()
        var shownRestoringToPictures = false
        for (source in paths) {
            var destination = source.removePrefix(recycleBinPath)

            val destinationParent = destination.getParentPath()
            if (isRestrictedWithSAFSdk30(destinationParent) && !isInDownloadDir(destinationParent)) {
                // if the file is not writeable on SDK30+, change it to Pictures
                val picturesDirectory = getPicturesDirectoryPath(destination)
                destination = File(picturesDirectory, destination.getFilenameFromPath()).path
                if (!shownRestoringToPictures) {
                    toast(getString(R.string.restore_to_path, humanizePath(picturesDirectory)))
                    shownRestoringToPictures = true
                }
            }

            val lastModified = File(source).lastModified()

            val isShowingSAF = handleSAFDialog(destination) {}
            if (isShowingSAF) {
                return@ensureBackgroundThread
            }

            val isShowingSAFSdk30 = handleSAFDialogSdk30(destination) {}
            if (isShowingSAFSdk30) {
                return@ensureBackgroundThread
            }

            if (getDoesFilePathExist(destination)) {
                val newFile = getAlternativeFile(File(destination))
                destination = newFile.path
            }

            var inputStream: InputStream? = null
            var out: OutputStream? = null
            try {
                out = getFileOutputStreamSync(destination, source.getMimeType())
                inputStream = getFileInputStreamSync(source)

                var copiedSize = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = inputStream!!.read(buffer)
                while (bytes >= 0) {
                    out!!.write(buffer, 0, bytes)
                    copiedSize += bytes
                    bytes = inputStream.read(buffer)
                }

                out?.flush()

                if (File(source).length() == copiedSize) {
                    mediaDB.updateDeleted(destination.removePrefix(recycleBinPath), 0, "$RECYCLE_BIN${source.removePrefix(recycleBinPath)}")
                }
                newPaths.add(destination)

                if (config.keepLastModified && lastModified != 0L) {
                    File(destination).setLastModified(lastModified)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            } finally {
                inputStream?.close()
                out?.close()
            }
        }

        runOnUiThread {
            callback()
        }

        rescanPaths(newPaths) {
            fixDateTaken(newPaths, false)
        }
    }
}

fun BaseSimpleActivity.emptyTheRecycleBin(callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        try {
            recycleBin.deleteRecursively()
            mediaDB.clearRecycleBin()
            directoryDB.deleteRecycleBin()
            toast(com.simplemobiletools.commons.R.string.recycle_bin_emptied)
            callback?.invoke()
        } catch (e: Exception) {
            toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
        }
    }
}

fun BaseSimpleActivity.emptyAndDisableTheRecycleBin(callback: () -> Unit) {
    ensureBackgroundThread {
        emptyTheRecycleBin {
            config.useRecycleBin = false
            callback()
        }
    }
}

fun BaseSimpleActivity.showRecycleBinEmptyingDialog(callback: () -> Unit) {
    ConfirmationDialog(
        this,
        "",
        com.simplemobiletools.commons.R.string.empty_recycle_bin_confirmation,
        com.simplemobiletools.commons.R.string.yes,
        com.simplemobiletools.commons.R.string.no
    ) {
        callback()
    }
}

fun BaseSimpleActivity.showRestoreConfirmationDialog(count: Int, callback: () -> Unit) {
    ConfirmationDialog(
        this,
        resources.getQuantityString(R.plurals.restore_confirmation, count, count),
        positive = com.simplemobiletools.commons.R.string.yes,
        negative = com.simplemobiletools.commons.R.string.no
    ) {
        callback()
    }
}

fun BaseSimpleActivity.updateFavoritePaths(fileDirItems: ArrayList<FileDirItem>, destination: String) {
    ensureBackgroundThread {
        fileDirItems.forEach {
            val newPath = "$destination/${it.name}"
            updateDBMediaPath(it.path, newPath)
        }
    }
}

fun Activity.hasNavBar(): Boolean {
    val display = windowManager.defaultDisplay

    val realDisplayMetrics = DisplayMetrics()
    display.getRealMetrics(realDisplayMetrics)

    val displayMetrics = DisplayMetrics()
    display.getMetrics(displayMetrics)

    return (realDisplayMetrics.widthPixels - displayMetrics.widthPixels > 0) || (realDisplayMetrics.heightPixels - displayMetrics.heightPixels > 0)
}

fun AppCompatActivity.fixDateTaken(
    paths: ArrayList<String>,
    showToasts: Boolean,
    hasRescanned: Boolean = false,
    callback: (() -> Unit)? = null
) {
    val BATCH_SIZE = 50
    if (showToasts && !hasRescanned) {
        toast(R.string.fixing)
    }

    val pathsToRescan = ArrayList<String>()
    ensureBackgroundThread {
        try {
            var didUpdateFile = false
            val operations = ArrayList<ContentProviderOperation>()

            val dateTakens = ArrayList<DateTaken>()

            for (path in paths) {
                try {
                    val dateTime: String = ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: ExifInterface(path).getAttribute(ExifInterface.TAG_DATETIME) ?: continue

                    // some formats contain a "T" in the middle, some don't
                    // sample dates: 2015-07-26T14:55:23, 2018:09:05 15:09:05
                    val t = if (dateTime.substring(10, 11) == "T") "\'T\'" else " "
                    val separator = dateTime.substring(4, 5)
                    val format = "yyyy${separator}MM${separator}dd${t}kk:mm:ss"
                    val formatter = SimpleDateFormat(format, Locale.getDefault())
                    val timestamp = formatter.parse(dateTime).time

                    val uri = getFileUri(path)
                    ContentProviderOperation.newUpdate(uri).apply {
                        val selection = "${Images.Media.DATA} = ?"
                        val selectionArgs = arrayOf(path)
                        withSelection(selection, selectionArgs)
                        withValue(Images.Media.DATE_TAKEN, timestamp)
                        operations.add(build())
                    }

                    if (operations.size % BATCH_SIZE == 0) {
                        contentResolver.applyBatch(MediaStore.AUTHORITY, operations)
                        operations.clear()
                    }

                    mediaDB.updateFavoriteDateTaken(path, timestamp)
                    didUpdateFile = true

                    val dateTaken = DateTaken(
                        null,
                        path,
                        path.getFilenameFromPath(),
                        path.getParentPath(),
                        timestamp,
                        (System.currentTimeMillis() / 1000).toInt(),
                        File(path).lastModified()
                    )
                    dateTakens.add(dateTaken)
                    if (!hasRescanned && getFileDateTaken(path) == 0L) {
                        pathsToRescan.add(path)
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            if (!didUpdateFile) {
                if (showToasts) {
                    toast(R.string.no_date_takens_found)
                }

                runOnUiThread {
                    callback?.invoke()
                }
                return@ensureBackgroundThread
            }

            val resultSize = contentResolver.applyBatch(MediaStore.AUTHORITY, operations).size
            if (resultSize == 0) {
                didUpdateFile = false
            }

            if (hasRescanned || pathsToRescan.isEmpty()) {
                if (dateTakens.isNotEmpty()) {
                    dateTakensDB.insertAll(dateTakens)
                }

                runOnUiThread {
                    if (showToasts) {
                        toast(if (didUpdateFile) R.string.dates_fixed_successfully else com.simplemobiletools.commons.R.string.unknown_error_occurred)
                    }

                    callback?.invoke()
                }
            } else {
                rescanPaths(pathsToRescan) {
                    fixDateTaken(paths, showToasts, true, callback)
                }
            }
        } catch (e: Exception) {
            if (showToasts) {
                showErrorToast(e)
            }
        }
    }
}

fun BaseSimpleActivity.saveRotatedImageToFile(oldPath: String, newPath: String, degrees: Int, showToasts: Boolean, callback: () -> Unit) {
    var newDegrees = degrees
    if (newDegrees < 0) {
        newDegrees += 360
    }

    if (oldPath == newPath && oldPath.isJpg()) {
        if (tryRotateByExif(oldPath, newDegrees, showToasts, callback)) {
            return
        }
    }

    val tmpPath = "$recycleBinPath/.tmp_${newPath.getFilenameFromPath()}"
    val tmpFileDirItem = FileDirItem(tmpPath, tmpPath.getFilenameFromPath())
    try {
        getFileOutputStream(tmpFileDirItem) {
            if (it == null) {
                if (showToasts) {
                    toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                }
                return@getFileOutputStream
            }

            val oldLastModified = File(oldPath).lastModified()
            if (oldPath.isJpg()) {
                // Release the truncating stream before saveAttributes(). An open handle makes that
                // call fail, and the fallback then re-encodes a small JPEG into a much larger one.
                it.close()
                copyFile(oldPath, tmpPath)
                if (!tryRewriteExifRotation(tmpPath, newDegrees) && !rotateJpegFallback(oldPath, tmpPath, newDegrees)) {
                    if (showToasts) {
                        toast(R.string.image_editing_failed)
                    }
                    return@getFileOutputStream
                }
                copyFile(tmpPath, newPath)
                rescanPaths(arrayListOf(newPath))
                fileRotatedSuccessfully(newPath, oldLastModified)
                callback.invoke()
                return@getFileOutputStream
            }

            val inputstream = getFileInputStreamSync(oldPath)
            val bitmap = BitmapFactory.decodeStream(inputstream)
            inputstream?.close()
            saveFile(tmpPath, bitmap, it as FileOutputStream, newDegrees, 90)

            copyFile(tmpPath, newPath)
            rescanPaths(arrayListOf(newPath))
            fileRotatedSuccessfully(newPath, oldLastModified)

            it.flush()
            it.close()
            callback.invoke()
        }
    } catch (e: OutOfMemoryError) {
        if (showToasts) {
            toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
        }
    } catch (e: Exception) {
        if (showToasts) {
            showErrorToast(e)
        }
    } finally {
        tryDeleteFileDirItem(tmpFileDirItem, false, true)
    }
}

@TargetApi(Build.VERSION_CODES.N)
fun Activity.tryRotateByExif(path: String, degrees: Int, showToasts: Boolean, callback: () -> Unit): Boolean {
    return try {
        val file = File(path)
        val oldLastModified = file.lastModified()
        if (saveImageRotation(path, degrees)) {
            fileRotatedSuccessfully(path, oldLastModified)
            callback.invoke()
            if (showToasts) {
                toast(com.simplemobiletools.commons.R.string.file_saved)
            }
            true
        } else {
            false
        }
    } catch (e: Exception) {
        // lets not show IOExceptions, rotating is saved just fine even with them
        if (showToasts && e !is IOException) {
            showErrorToast(e)
        }
        false
    }
}

private fun BaseSimpleActivity.tryRewriteExifRotation(path: String, degrees: Int): Boolean {
    return try {
        saveExifRotation(ExifInterface(path), degrees)
        true
    } catch (_: IOException) {
        false
    }
}

/**
 * EXIF orientation rewrite failed (corrupt markers or non-JPEG bytes in a .jpg).
 * First choice: lossless DCT rotate via mozjpeg — no quality or size change.
 * Last resort: decode and re-encode near the source quality and size.
 * [tmpPath] already holds a copy of the source bytes.
 */
private fun BaseSimpleActivity.rotateJpegFallback(sourcePath: String, tmpPath: String, extraDegrees: Int): Boolean {
    val existingDegrees = readExifDegrees(tmpPath)
    val totalDegrees = ((existingDegrees + extraDegrees) % 360 + 360) % 360
    if (totalDegrees != 0 && tryLosslessJpegRotate(tmpPath, totalDegrees, resetOrientation = existingDegrees != 0)) {
        return true
    }
    return writeRotatedBitmap(sourcePath, tmpPath, extraDegrees)
}

private fun readExifDegrees(path: String): Int {
    return try {
        ExifInterface(path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            .degreesFromOrientation()
    } catch (_: Exception) {
        0
    }
}

private fun tryLosslessJpegRotate(path: String, degrees: Int, resetOrientation: Boolean): Boolean {
    val rotated = File("$path.rot")
    if (!JpegTransform.rotate(path, rotated.absolutePath, degrees)) {
        rotated.delete()
        return false
    }
    if (resetOrientation) {
        // The transform rotated the pixels; a stale orientation tag would rotate them again.
        try {
            val exif = ExifInterface(rotated.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
        } catch (_: Exception) {
            rotated.delete()
            return false
        }
    }
    val dest = File(path)
    if (!rotated.renameTo(dest)) {
        try {
            rotated.copyTo(dest, overwrite = true)
        } catch (_: Exception) {
            rotated.delete()
            return false
        } finally {
            rotated.delete()
        }
    }
    return true
}

private fun BaseSimpleActivity.writeRotatedBitmap(sourcePath: String, destPath: String, extraDegrees: Int): Boolean {
    val existingDegrees = readExifDegrees(sourcePath)
    val totalDegrees = ((existingDegrees + extraDegrees) % 360 + 360) % 360
    val targetBytes = File(sourcePath).length()
    val qualityGuess = peekJpegQuality(sourcePath)
    val input = getFileInputStreamSync(sourcePath) ?: return false
    return try {
        val bitmap = BitmapFactory.decodeStream(input) ?: return false
        val matrix = Matrix()
        matrix.postRotate(totalDegrees.toFloat())
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        try {
            val encoded = jpegBytesNearSize(rotated, targetBytes, qualityGuess)
            FileOutputStream(File(destPath)).use { it.write(encoded) }
        } finally {
            if (rotated !== bitmap && !rotated.isRecycled) {
                rotated.recycle()
            }
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        true
    } finally {
        input.close()
    }
}

internal fun BaseSimpleActivity.peekJpegQuality(path: String): Int? {
    val input = getFileInputStreamSync(path) ?: return null
    return try {
        JpegQuality.estimate(input.buffered())
    } catch (_: Exception) {
        null
    } finally {
        input.close()
    }
}

internal fun jpegBytesNearSize(bitmap: Bitmap, targetBytes: Long, qualityGuess: Int?): ByteArray {
    val firstQuality = qualityGuess ?: 85
    val first = jpegBytes(bitmap, firstQuality)
    if (targetBytes <= 0L || first.size <= targetBytes * 5 / 4) {
        return first
    }
    // Floor 25: sources compressed harder than Android's encoder reaches at
    // higher qualities (webp-recompressed, messenger downloads) need low values,
    // and below ~25 artifacts outweigh any size win.
    var low = 25
    var high = (firstQuality - 1).coerceAtLeast(low)
    var best = first
    var bestDiff = kotlin.math.abs(first.size.toLong() - targetBytes)
    repeat(7) {
        if (low > high) {
            return@repeat
        }
        val quality = (low + high) / 2
        val encoded = jpegBytes(bitmap, quality)
        val diff = kotlin.math.abs(encoded.size.toLong() - targetBytes)
        if (diff < bestDiff) {
            best = encoded
            bestDiff = diff
        }
        if (encoded.size.toLong() > targetBytes) {
            high = quality - 1
        } else {
            low = quality + 1
        }
    }
    return best
}

private fun jpegBytes(bitmap: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
    return out.toByteArray()
}

fun Activity.fileRotatedSuccessfully(path: String, lastModified: Long) {
    if (config.keepLastModified && lastModified != 0L) {
        File(path).setLastModified(lastModified)
        updateLastModified(path, lastModified)
    }

    Picasso.get().invalidate(path.getFileKey(lastModified))
    // we cannot refresh a specific image in Glide Cache, so just clear it all
    val glide = Glide.get(applicationContext)
    glide.clearDiskCache()
    runOnUiThread {
        glide.clearMemory()
    }
}

fun BaseSimpleActivity.copyFile(source: String, destination: String) {
    var inputStream: InputStream? = null
    var out: OutputStream? = null
    try {
        out = getFileOutputStreamSync(destination, source.getMimeType())
        inputStream = getFileInputStreamSync(source)
        inputStream!!.copyTo(out!!)
    } catch (e: Exception) {
        showErrorToast(e)
    } finally {
        inputStream?.close()
        out?.close()
    }
}

fun BaseSimpleActivity.ensureWriteAccess(path: String, callback: () -> Unit) {
    when {
        isRestrictedSAFOnlyRoot(path) -> {
            handleAndroidSAFDialog(path) {
                if (!it) {
                    return@handleAndroidSAFDialog
                }
                callback.invoke()
            }
        }

        needsStupidWritePermissions(path) -> {
            handleSAFDialog(path) {
                if (!it) {
                    return@handleSAFDialog
                }
                callback()
            }
        }

        isAccessibleWithSAFSdk30(path) -> {
            handleSAFDialogSdk30(path) {
                if (!it) {
                    return@handleSAFDialogSdk30
                }
                callback()
            }
        }

        else -> {
            callback()
        }
    }
}

fun BaseSimpleActivity.launchResizeMultipleImagesDialog(paths: List<String>, callback: (() -> Unit)? = null) {
    ensureBackgroundThread {
        val imagePaths = mutableListOf<String>()
        val imageSizes = mutableListOf<Point>()
        for (path in paths) {
            val size = path.getImageResolution(this)
            if (size != null) {
                imagePaths.add(path)
                imageSizes.add(size)
            }
        }

        runOnUiThread {
            ResizeMultipleImagesDialog(this, imagePaths, imageSizes) {
                callback?.invoke()
            }
        }
    }
}

fun BaseSimpleActivity.launchResizeImageDialog(path: String, callback: (() -> Unit)? = null) {
    val originalSize = path.getImageResolution(this) ?: return
    ResizeWithPathDialog(this, originalSize, path) { newSize, newPath ->
        ensureBackgroundThread {
            val file = File(newPath)
            val pathLastModifiedMap = mapOf(file.absolutePath to file.lastModified())
            try {
                resizeImage(path, newPath, newSize) { success ->
                    if (success) {
                        toast(com.simplemobiletools.commons.R.string.file_saved)

                        val paths = arrayListOf(file.absolutePath)
                        rescanPathsAndUpdateLastModified(paths, pathLastModifiedMap) {
                            runOnUiThread {
                                callback?.invoke()
                            }
                        }
                    } else {
                        toast(R.string.image_editing_failed)
                    }
                }
            } catch (e: OutOfMemoryError) {
                toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }
}

fun BaseSimpleActivity.resizeImage(oldPath: String, newPath: String, size: Point, callback: (success: Boolean) -> Unit) {
    var oldExif: ExifInterface? = null
    if (isNougatPlus()) {
        val inputStream = contentResolver.openInputStream(Uri.fromFile(File(oldPath)))
        oldExif = ExifInterface(inputStream!!)
    }

    val newBitmap = Glide.with(applicationContext).asBitmap().load(oldPath).submit(size.x, size.y).get()

    val newFile = File(newPath)
    val newFileDirItem = FileDirItem(newPath, newPath.getFilenameFromPath())
    getFileOutputStream(newFileDirItem, true) { out ->
        if (out != null) {
            out.use {
                try {
                    newBitmap.compress(newFile.absolutePath.getCompressionFormat(), 90, it)
                    writeExif(oldExif, exifUriForPath(newPath))
                    callback(true)
                } catch (e: Exception) {
                    callback(false)
                }
            }
        } else {
            callback(false)
        }
    }
}

fun BaseSimpleActivity.rescanPathsAndUpdateLastModified(paths: ArrayList<String>, pathLastModifiedMap: Map<String, Long>, callback: () -> Unit) {
    fixDateTaken(paths, false)
    for (path in paths) {
        val file = File(path)
        val lastModified = pathLastModifiedMap[path]
        if (config.keepLastModified && lastModified != null && lastModified != 0L) {
            File(file.absolutePath).setLastModified(lastModified)
            updateLastModified(file.absolutePath, lastModified)
        }
    }
    rescanPaths(paths, callback)
}

fun saveFile(path: String, bitmap: Bitmap, out: FileOutputStream, degrees: Int, quality: Int) {
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    val bmp = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    bmp.compress(path.getCompressionFormat(), quality, out)
}

fun Activity.getShortcutImage(tmb: String, drawable: Drawable, callback: () -> Unit) {
    ensureBackgroundThread {
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .fitCenter()

        val size = resources.getDimension(com.simplemobiletools.commons.R.dimen.shortcut_size).toInt()
        val builder = Glide.with(this)
            .asDrawable()
            .load(tmb)
            .apply(options)
            .centerCrop()
            .into(size, size)

        try {
            (drawable as LayerDrawable).setDrawableByLayerId(R.id.shortcut_image, builder.get())
        } catch (e: Exception) {
        }

        runOnUiThread {
            callback()
        }
    }
}

@TargetApi(Build.VERSION_CODES.N)
fun Activity.showFileOnMap(path: String) {
    val exif = try {
        if (path.startsWith("content://") && isNougatPlus()) {
            ExifInterface(contentResolver.openInputStream(Uri.parse(path))!!)
        } else {
            ExifInterface(path)
        }
    } catch (e: Exception) {
        showErrorToast(e)
        return
    }

    val latLon = FloatArray(2)
    if (exif.getLatLong(latLon)) {
        showLocationOnMap("${latLon[0]}, ${latLon[1]}")
    } else {
        toast(R.string.unknown_location)
    }
}

fun Activity.handleExcludedFolderPasswordProtection(callback: () -> Unit) {
    if (config.isExcludedPasswordProtectionOn) {
        SecurityDialog(this, config.excludedPasswordHash, config.excludedProtectionType) { _, _, success ->
            if (success) {
                callback()
            }
        }
    } else {
        callback()
    }
}

fun Activity.openRecycleBin() {
    Intent(this, MediaActivity::class.java).apply {
        putExtra(DIRECTORY, RECYCLE_BIN)
        startActivity(this)
    }
}
