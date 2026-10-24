package tomato.gallery.extensions

import android.annotation.TargetApi
import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
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
import tomato.gallery.helpers.PATH
import tomato.gallery.helpers.putInternalNonce
import com.simplemobiletools.commons.models.FileDirItem
import tomato.gallery.BuildConfig
import tomato.gallery.R
import tomato.gallery.activities.MediaActivity
import tomato.gallery.activities.SettingsActivity
import tomato.gallery.activities.SimpleActivity
import tomato.gallery.dialogs.AllFilesPermissionDialog
import tomato.gallery.dialogs.PickDirectoryDialog
import tomato.gallery.dialogs.ResizeMultipleImagesDialog
import tomato.gallery.dialogs.ResizeWithPathDialog
import tomato.gallery.helpers.DIRECTORY
import tomato.gallery.helpers.JpegQuality
import tomato.gallery.helpers.JpegTransform
import tomato.gallery.helpers.MetadataStripper
import tomato.gallery.helpers.RECYCLE_BIN
import tomato.gallery.helpers.REQUEST_FAVORITE_SYSTEM
import tomato.gallery.helpers.REQUEST_SYSTEM_TRASH
import tomato.gallery.models.DateTaken
import tomato.gallery.models.Medium
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
    shareMediaPaths(arrayListOf(path))
}

/**
 * Shares [paths]; with "strip metadata on share" on, every item is copied to cache/share and stripped first.
 * An item that can't be stripped (HEIC, video, DNG, unreadable...) is left out with a toast, never shared
 * with its metadata. [paths] may hold content:// strings (the video player passes one).
 */
fun Activity.shareMediaPaths(paths: ArrayList<String>) {
    if (paths.isEmpty()) {
        return
    }

    val activity = this
    if (activity is BaseSimpleActivity && activity.config.stripMetadataOnShare) {
        ensureBackgroundThread {
            val shareDir = File(activity.cacheDir, "share")
            // the previous share is done by now, don't let stripped copies pile up
            shareDir.deleteRecursively()
            shareDir.mkdirs()

            val stripped = ArrayList<String>()
            var failed = 0
            paths.forEachIndexed { index, path ->
                // one subfolder per item, so two "IMG_0001.jpg" from different folders don't overwrite each other
                val dest = File(File(shareDir, index.toString()), activity.getShareFilename(path))
                val ok = try {
                    dest.parentFile?.mkdirs()
                    activity.copyToShareCache(path, dest) && MetadataStripper.stripAll(activity, dest.absolutePath)
                } catch (_: Exception) {
                    false
                }

                if (ok) {
                    stripped.add(dest.absolutePath)
                } else {
                    dest.delete()
                    failed++
                }
            }

            activity.runOnUiThread {
                if (failed > 0) {
                    activity.toast(activity.getString(R.string.share_strip_failed_skipped, failed), android.widget.Toast.LENGTH_LONG)
                }
                when {
                    stripped.size == 1 -> sharePath(stripped.first())
                    stripped.size > 1 -> sharePaths(stripped)
                }
            }
        }
        return
    }

    if (paths.size == 1) {
        sharePath(paths.first())
    } else {
        sharePaths(paths)
    }
}

private fun Activity.getShareFilename(path: String): String {
    val name = if (path.startsWith("content://")) {
        try {
            contentResolver.query(Uri.parse(path), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: Uri.parse(path).lastPathSegment?.substringAfterLast('/')
    } else {
        File(path.removePrefix("file://")).name
    }
    // never let a provider-supplied name escape the share folder
    return name?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "shared_file"
}

private fun Activity.copyToShareCache(path: String, dest: File): Boolean {
    val input = if (path.startsWith("content://")) {
        contentResolver.openInputStream(Uri.parse(path))
    } else {
        FileInputStream(File(path.removePrefix("file://")))
    } ?: return false

    input.use { inputStream ->
        FileOutputStream(dest).use { out ->
            inputStream.copyTo(out)
        }
    }
    return dest.length() > 0L
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
            Intent(applicationContext, tomato.gallery.activities.VideoPlayerActivity::class.java).apply {
                setDataAndType(newUri, mimeType)
                putExtra(PATH, path)
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
        startActivityForResult(Intent(this, tomato.gallery.activities.EditActivity::class.java).apply {
            action = Intent.ACTION_EDIT
            setDataAndType(uri, mime)
            putExtra(REAL_FILE_PATH, newPath)
            putInternalNonce()
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

private val visibilityTogglePaths = mutableSetOf<String>()

fun BaseSimpleActivity.toggleFileVisibility(oldPath: String, hide: Boolean, callback: ((newPath: String) -> Unit)? = null): Boolean {
    synchronized(visibilityTogglePaths) {
        if (!visibilityTogglePaths.add(oldPath)) {
            return false
        }
    }
    val release = {
        synchronized(visibilityTogglePaths) {
            visibilityTogglePaths.remove(oldPath)
        }
    }
    val path = oldPath.getParentPath()
    var filename = oldPath.getFilenameFromPath()
    if ((hide && filename.startsWith('.')) || (!hide && !filename.startsWith('.'))) {
        release()
        callback?.invoke(oldPath)
        return true
    }

    filename = if (hide) {
        ".${filename.trimStart('.')}"
    } else {
        filename.substring(1, filename.length)
    }

    val newPath = "$path/$filename"
    renameFile(oldPath, newPath, false) { success, useAndroid30Way ->
        if (!success) {
            // nothing was renamed: keep the DB as is and report the unchanged path
            runOnUiThread {
                release()
                callback?.invoke(oldPath)
            }
            return@renameFile
        }

        ensureBackgroundThread {
            updateDBMediaPath(oldPath, newPath)
            runOnUiThread {
                release()
                callback?.invoke(newPath)
            }
        }
    }
    return true
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
                // also drops favorite, tags and date taken once the file is really gone
                onPathDeleted(fileDirItem.path)
                runOnUiThread {
                    callback?.invoke(it)
                }
            }
        } else {
            callback?.invoke(it)
        }
    }
}

/**
 * All-or-nothing bin move for callers that delete every original on success. If any file fails, the ones
 * already copied are taken back out of the bin (copy deleted, rows reverted) and the callback gets false,
 * so no original is deleted while its row points at the bin.
 */
fun BaseSimpleActivity.movePathsInRecycleBin(paths: ArrayList<String>, callback: ((wasSuccess: Boolean) -> Unit)?) {
    if (shouldUseSystemTrash()) {
        trashPathsWithSystem(paths, callback)
        return
    }
    movePathsInRecycleBinPartially(paths) { moved ->
        if (moved.size == paths.size) {
            callback?.invoke(true)
        } else {
            ensureBackgroundThread {
                moved.forEach { undoRecycleBinCopy(it) }
                callback?.invoke(false)
            }
        }
    }
}

/**
 * Copies each path into the app's recycle bin and points its media row (inserted when missing) and tags at
 * the bin copy. [callback] gets the original paths that were copied successfully, on a background thread:
 * only those originals may be deleted. Does not handle the system trash, check [shouldUseSystemTrash] first.
 */
fun BaseSimpleActivity.movePathsInRecycleBinPartially(paths: ArrayList<String>, callback: (movedPaths: ArrayList<String>) -> Unit) {
    ensureBackgroundThread {
        val moved = ArrayList<String>()
        val OTGPath = config.OTGPath
        var shownError = false

        for (source in paths) {
            // a second "photo.jpg" deleted from the same folder must not replace the copy already in the bin
            var internalFile = File(recycleBinPath, source)
            if (internalFile.exists()) {
                internalFile = getAlternativeFile(internalFile)
            }
            val binKey = "$RECYCLE_BIN${internalFile.absolutePath.removePrefix(recycleBinPath)}"

            val copied = try {
                if (OTGPath.isNotEmpty() && source.startsWith(OTGPath)) {
                    copyOtgFileToRecycleBin(source, internalFile)
                } else {
                    val file = File(source)
                    val lastModified = file.lastModified()
                    internalFile.parentFile?.mkdirs()
                    val ok = file.isFile && file.copyRecursively(internalFile, true) && internalFile.length() == file.length()
                    if (ok && config.keepLastModified && lastModified != 0L) {
                        internalFile.setLastModified(lastModified)
                    }
                    ok
                }
            } catch (e: Exception) {
                if (!shownError) {
                    showErrorToast(e)
                    shownError = true
                }
                false
            }

            if (!copied) {
                internalFile.delete()
                continue
            }

            try {
                val deletedTS = System.currentTimeMillis()
                if (mediaDB.updateDeleted(binKey, deletedTS, source) == 0) {
                    // no cached row (stack members, Search, uncached files): add one, or the file never shows up in the bin
                    mediaDB.insert(getRecycleBinMedium(source, binKey, internalFile, deletedTS))
                }
                tagsDB.updatePath(oldPath = source, newPath = binKey)
                moved.add(source)
            } catch (e: Exception) {
                internalFile.delete()
                if (!shownError) {
                    showErrorToast(e)
                    shownError = true
                }
            }
        }
        callback(moved)
    }
}

private fun BaseSimpleActivity.copyOtgFileToRecycleBin(source: String, internalFile: File): Boolean {
    val fileDocument = getSomeDocumentFile(source) ?: return false
    var copiedSize = 0L
    val inputStream = applicationContext.contentResolver.openInputStream(fileDocument.uri) ?: return false
    inputStream.use { input ->
        internalFile.parentFile?.mkdirs()
        FileOutputStream(internalFile).use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytes = input.read(buffer)
            while (bytes >= 0) {
                out.write(buffer, 0, bytes)
                copiedSize += bytes
                bytes = input.read(buffer)
            }
            out.flush()
        }
    }
    return fileDocument.getItemSize(true) == copiedSize && internalFile.exists()
}

private fun Context.getRecycleBinMedium(source: String, binKey: String, internalFile: File, deletedTS: Long): Medium {
    val type = getMediumTypeFromPath(source)
    val lastModified = internalFile.lastModified()
    return Medium(
        null, source.getFilenameFromPath(), binKey, source.getParentPath(), lastModified, lastModified,
        internalFile.length(), type, 0, false, deletedTS, 0L
    )
}

// Reverts one successful bin copy when the batch it belongs to is rolled back; the original was not deleted.
private fun BaseSimpleActivity.undoRecycleBinCopy(source: String) {
    try {
        val deleted = mediaDB.getDeletedMedia().filter { medium ->
            medium.parentPath == source.getParentPath() && medium.name == source.getFilenameFromPath()
        }
        // newest first: the copy made by this batch
        val row = deleted.maxByOrNull { it.deletedTS } ?: return
        File(recycleBinPath, row.path.removePrefix(RECYCLE_BIN)).delete()
        mediaDB.updateDeleted(source, 0, row.path)
        tagsDB.updatePath(oldPath = row.path, newPath = source)
    } catch (_: Exception) {
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
            var copiedSize = -1L
            try {
                out = getFileOutputStreamSync(destination, source.getMimeType())
                inputStream = getFileInputStreamSync(source)

                var copied = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = inputStream!!.read(buffer)
                while (bytes >= 0) {
                    out!!.write(buffer, 0, bytes)
                    copied += bytes
                    bytes = inputStream.read(buffer)
                }

                out?.flush()
                copiedSize = copied
            } catch (e: Exception) {
                showErrorToast(e)
            } finally {
                try {
                    inputStream?.close()
                } catch (_: Exception) {
                }
                try {
                    out?.close()
                } catch (_: Exception) {
                }
            }

            if (copiedSize < 0L || File(source).length() != copiedSize) {
                // short copy (disk full...): never rescan a truncated file into the gallery, keep the bin copy
                tryDeleteDestinationQuietly(destination)
                continue
            }

            try {
                val binKey = "$RECYCLE_BIN${source.removePrefix(recycleBinPath)}"
                mediaDB.updateDeleted(destination, 0, binKey)
                // the restore target may differ from the original path (name taken, Pictures fallback)
                mediaDB.updateMedium(
                    oldPath = destination, newParentPath = destination.getParentPath(), newFilename = destination.getFilenameFromPath(),
                    newFullPath = destination
                )
                tagsDB.updatePath(oldPath = binKey, newPath = destination)
            } catch (_: Exception) {
            }
            newPaths.add(destination)

            if (config.keepLastModified && lastModified != 0L) {
                File(destination).setLastModified(lastModified)
            }

            // the copy is verified, free the space used by the bin copy
            File(source).delete()
        }

        runOnUiThread {
            callback()
        }

        rescanPaths(newPaths) {
            fixDateTaken(newPaths, false)
        }
    }
}

private fun BaseSimpleActivity.tryDeleteDestinationQuietly(path: String) {
    try {
        val file = File(path)
        if (file.exists() && !file.delete() && getDoesFilePathExist(path)) {
            getSomeDocumentFile(path)?.delete()
        }
    } catch (_: Exception) {
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
    saveRotatedImageToFileResult(oldPath, newPath, degrees, showToasts) { success ->
        if (success) {
            callback()
        }
    }
}

private val ROTATABLE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

/**
 * Only JPEG, PNG and WebP can be written back after a rotate. Anything else (HEIC, AVIF, JXL, BMP...) would get
 * JPEG bytes under its own name and lose HDR, animation and EXIF.
 */
fun String.canSaveRotatedAs(): Boolean = getFilenameExtension().lowercase() in ROTATABLE_EXTENSIONS

/**
 * Same as [saveRotatedImageToFile], but always reports the outcome so batch callers can count failures.
 * With [clearGlideCache] false the caller clears the Glide cache once after the whole batch.
 * The callback runs on the calling thread for early failures, otherwise on whichever thread finished the work.
 */
fun BaseSimpleActivity.saveRotatedImageToFileResult(
    oldPath: String, newPath: String, degrees: Int, showToasts: Boolean, clearGlideCache: Boolean = true, callback: (success: Boolean) -> Unit
) {
    var newDegrees = degrees % 360
    if (newDegrees < 0) {
        newDegrees += 360
    }

    // never write the encoder's format under another format's extension, never flatten an animation in place
    if (!newPath.canSaveRotatedAs() || (oldPath == newPath && isAnimatedImage(oldPath))) {
        if (showToasts) {
            toast(R.string.rotate_format_unsupported)
        }
        callback(false)
        return
    }

    if (oldPath == newPath && oldPath.isJpg()) {
        if (tryRotateByExif(oldPath, newDegrees, showToasts, clearGlideCache) { callback(true) }) {
            return
        }
    }

    val tmpPath = "$recycleBinPath/.tmp_${System.nanoTime()}_${newPath.getFilenameFromPath()}"
    var keepTmp = false
    var success = false
    try {
        val oldLastModified = File(oldPath).lastModified()
        File(tmpPath).parentFile?.mkdirs()
        val prepared = if (oldPath.isJpg() && newPath.isJpg()) {
            copyFile(oldPath, tmpPath) && (tryRewriteExifRotation(tmpPath, newDegrees) || rotateJpegFallback(oldPath, tmpPath, newDegrees))
        } else {
            writeRotatedNonJpeg(oldPath, newPath, tmpPath, newDegrees)
        }

        if (!prepared) {
            if (showToasts) {
                toast(R.string.image_editing_failed)
            }
        } else if (!replaceFileSafely(tmpPath, newPath)) {
            // the destination may be damaged; the rotated copy is the only intact result, keep it
            keepTmp = true
            if (showToasts) {
                toast(R.string.image_editing_failed)
            }
        } else {
            rescanPaths(arrayListOf(newPath))
            fileRotatedSuccessfully(newPath, oldLastModified, clearGlideCache)
            success = true
        }
    } catch (e: OutOfMemoryError) {
        if (showToasts) {
            toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
        }
    } catch (e: Throwable) {
        if (showToasts && e is Exception) {
            showErrorToast(e)
        }
    } finally {
        if (!keepTmp) {
            File(tmpPath).delete()
        }
    }
    callback(success)
}

/** PNG, WebP (or a JPEG written from another source): decode, rotate, encode in the format of [newPath], copy EXIF. */
private fun BaseSimpleActivity.writeRotatedNonJpeg(oldPath: String, newPath: String, tmpPath: String, degrees: Int): Boolean {
    val bitmap = getFileInputStreamSync(oldPath)?.use { BitmapFactory.decodeStream(it) } ?: return false
    val matrix = Matrix()
    matrix.postRotate(degrees.toFloat())
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    val encoded = try {
        val out = ByteArrayOutputStream()
        val extension = newPath.getFilenameExtension().lowercase()
        val ok = when (extension) {
            "png" -> rotated.compress(Bitmap.CompressFormat.PNG, 100, out)
            "webp" -> if (isLosslessWebp(oldPath) || oldPath.isPng()) {
                if (isRPlus()) {
                    rotated.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
                } else {
                    // the legacy WEBP format is lossless at quality 100 (Android 10+)
                    @Suppress("DEPRECATION")
                    rotated.compress(Bitmap.CompressFormat.WEBP, 100, out)
                }
            } else if (isRPlus()) {
                rotated.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out)
            } else {
                @Suppress("DEPRECATION")
                rotated.compress(Bitmap.CompressFormat.WEBP, 90, out)
            }

            else -> rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        if (!ok) null else out.toByteArray()
    } finally {
        if (rotated !== bitmap && !rotated.isRecycled) {
            rotated.recycle()
        }
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    if (encoded == null || encoded.isEmpty()) {
        return false
    }
    FileOutputStream(File(tmpPath)).use { it.write(encoded) }
    copyExifWithoutOrientation(oldPath, tmpPath)
    return true
}

// Keeps date, GPS and the rest of the EXIF; pixels are already rotated, so the orientation tag stays normal.
private fun BaseSimpleActivity.copyExifWithoutOrientation(sourcePath: String, destPath: String) {
    try {
        val sourceExif = getFileInputStreamSync(sourcePath)?.use { ExifInterface(it) } ?: return
        val destExif = ExifInterface(destPath)
        sourceExif.copyNonDimensionAttributesTo(destExif)
    } catch (_: Exception) {
    }
}

private fun BaseSimpleActivity.readHeader(path: String, size: Int): ByteArray? {
    return try {
        getFileInputStreamSync(path)?.use { input ->
            val buffer = ByteArray(size)
            var total = 0
            while (total < size) {
                val read = input.read(buffer, total, size - total)
                if (read < 0) {
                    break
                }
                total += read
            }
            buffer.copyOf(total)
        }
    } catch (_: Exception) {
        null
    }
}

private fun ByteArray.containsAscii(text: String): Boolean {
    val needle = text.toByteArray(Charsets.US_ASCII)
    if (needle.isEmpty() || size < needle.size) {
        return false
    }
    for (i in 0..size - needle.size) {
        var match = true
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) {
            return true
        }
    }
    return false
}

// Animated WebP ("ANIM" chunk), APNG saved as .png ("acTL" before the image data) or GIF: a decode keeps one frame only.
private fun BaseSimpleActivity.isAnimatedImage(path: String): Boolean {
    return when {
        path.isGif() -> true
        path.isWebP() -> readHeader(path, 64)?.containsAscii("ANIM") == true
        path.isPng() -> readHeader(path, 64 * 1024)?.containsAscii("acTL") == true
        else -> false
    }
}

private fun BaseSimpleActivity.isLosslessWebp(path: String): Boolean {
    if (!path.isWebP()) {
        return false
    }
    val header = readHeader(path, 64 * 1024) ?: return false
    return header.containsAscii("VP8L") && !header.containsAscii("VP8 ")
}

@TargetApi(Build.VERSION_CODES.N)
fun Activity.tryRotateByExif(path: String, degrees: Int, showToasts: Boolean, callback: () -> Unit): Boolean {
    return tryRotateByExif(path, degrees, showToasts, true, callback)
}

@TargetApi(Build.VERSION_CODES.N)
fun Activity.tryRotateByExif(path: String, degrees: Int, showToasts: Boolean, clearGlideCache: Boolean, callback: () -> Unit): Boolean {
    return try {
        val file = File(path)
        val oldLastModified = file.lastModified()
        if (saveImageRotation(path, degrees)) {
            fileRotatedSuccessfully(path, oldLastModified, clearGlideCache)
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
    } catch (_: Exception) {
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
    return try {
        // The decode plus the rotated copy need two full bitmaps; sample down instead of crashing with OOM.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // a bounds-only decode always returns null, only the stream itself may be missing
        val boundsInput = getFileInputStreamSync(sourcePath) ?: return false
        boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
        val budget = Runtime.getRuntime().maxMemory() / 3
        var sampleSize = 1
        while (bounds.outWidth > 0 && bounds.outHeight > 0 &&
            2L * 4L * (bounds.outWidth / sampleSize).toLong() * (bounds.outHeight / sampleSize).toLong() > budget
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = getFileInputStreamSync(sourcePath)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return false
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
        // keep date, GPS and camera tags; the pixels are rotated, the new file has no orientation tag
        copyExifWithoutOrientation(sourcePath, destPath)
        true
    } catch (_: Exception) {
        false
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

fun Activity.fileRotatedSuccessfully(path: String, lastModified: Long, clearGlideCache: Boolean = true) {
    if (config.keepLastModified && lastModified != 0L) {
        File(path).setLastModified(lastModified)
        updateLastModified(path, lastModified)
    }

    Picasso.get().invalidate(path.getFileKey(lastModified))
    if (clearGlideCache) {
        clearGlideCaches()
    }
}

// We cannot refresh a specific image in the Glide cache, so clear it all. Call it off the UI thread.
fun Activity.clearGlideCaches() {
    val glide = Glide.get(applicationContext)
    glide.clearDiskCache()
    runOnUiThread {
        glide.clearMemory()
    }
}

/**
 * Copies [source] over [destination]. Returns false (after a toast) when anything fails or the copy came out
 * shorter than the source, so callers never report a truncated file as saved.
 */
fun BaseSimpleActivity.copyFile(source: String, destination: String): Boolean {
    var inputStream: InputStream? = null
    var out: OutputStream? = null
    var copied = -1L
    try {
        out = getFileOutputStreamSync(destination, source.getMimeType())
        inputStream = getFileInputStreamSync(source)
        copied = inputStream!!.copyTo(out!!)
        out.flush()
    } catch (e: Exception) {
        showErrorToast(e)
        copied = -1L
    } finally {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            out?.close()
        } catch (_: Exception) {
            copied = -1L
        }
    }

    if (copied < 0L) {
        return false
    }
    val sourceFile = File(source)
    return !sourceFile.isFile || sourceFile.length() == copied
}

/**
 * Replaces [destination] with [source] without truncating it first when the folder is directly writable:
 * the bytes go to a hidden sibling, are synced, then renamed over the destination. If the sibling can't even
 * be created (SAF, scoped storage), falls back to [copyFile]. Returns false when the destination was not replaced.
 */
fun BaseSimpleActivity.replaceFileSafely(source: String, destination: String): Boolean {
    val destFile = File(destination)
    val parent = destFile.parentFile
    if (parent != null && parent.canWrite() && !needsStupidWritePermissions(destination) && !isRestrictedSAFOnlyRoot(destination)) {
        val sibling = File(parent, ".${destFile.name}.tmp${System.nanoTime()}")
        var created = false
        try {
            FileInputStream(File(source)).use { input ->
                FileOutputStream(sibling).use { out ->
                    created = true
                    input.copyTo(out)
                    out.flush()
                    out.fd.sync()
                }
            }
            if (sibling.length() != File(source).length()) {
                sibling.delete()
                return false
            }
            if (sibling.renameTo(destFile)) {
                return true
            }
            sibling.delete()
        } catch (e: Exception) {
            sibling.delete()
            if (created) {
                // ran out of space or similar while writing: the original is untouched, don't retry over it
                return false
            }
        }
    }
    return copyFile(source, destination)
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

/**
 * Encodes into memory and a private temp file first, then replaces [newPath]; the original is never truncated
 * before the new image exists. Only jpg/png/webp targets are written, the encoder's format must match the name.
 */
fun BaseSimpleActivity.resizeImage(oldPath: String, newPath: String, size: Point, callback: (success: Boolean) -> Unit) {
    if (!newPath.canSaveRotatedAs()) {
        callback(false)
        return
    }

    val tmpFile = File(recycleBinPath, ".tmp_resize_${System.nanoTime()}_${newPath.getFilenameFromPath()}")
    val success = try {
        val oldExif = try {
            getFileInputStreamSync(oldPath)?.use { ExifInterface(it) }
        } catch (_: Exception) {
            null
        }

        val newBitmap = Glide.with(applicationContext).asBitmap().load(oldPath).submit(size.x, size.y).get()
        val encoded = ByteArrayOutputStream().use { bytes ->
            if (!newBitmap.compress(newPath.getCompressionFormat(), 90, bytes)) {
                null
            } else {
                bytes.toByteArray()
            }
        }

        if (encoded == null || encoded.isEmpty()) {
            false
        } else {
            tmpFile.parentFile?.mkdirs()
            FileOutputStream(tmpFile).use {
                it.write(encoded)
                it.flush()
                it.fd.sync()
            }
            if (oldExif != null) {
                try {
                    oldExif.copyNonDimensionAttributesTo(ExifInterface(tmpFile.absolutePath))
                } catch (_: Exception) {
                }
            }
            replaceFileSafely(tmpFile.absolutePath, newPath)
        }
    } catch (e: Throwable) {
        false
    } finally {
        tmpFile.delete()
    }
    callback(success)
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
