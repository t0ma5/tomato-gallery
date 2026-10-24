package tomato.gallery.activities

import android.annotation.TargetApi
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.os.Looper
import androidx.activity.OnBackPressedCallback
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.resource.bitmap.Downsampler
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.canhub.cropper.CropImageView
import com.simplemobiletools.commons.dialogs.ColorPickerDialog
import com.simplemobiletools.commons.dialogs.ConfirmationDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.NavigationIcon
import com.simplemobiletools.commons.helpers.REAL_FILE_PATH
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.commons.helpers.isNougatPlus
import com.simplemobiletools.commons.models.FileDirItem
import tomato.gallery.BuildConfig
import tomato.gallery.R
import tomato.gallery.adapters.FiltersAdapter
import tomato.gallery.databinding.ActivityEditBinding
import tomato.gallery.dialogs.AddStickerDialog
import tomato.gallery.dialogs.AddTextDialog
import tomato.gallery.dialogs.FontPickerDialog
import tomato.gallery.dialogs.OtherAspectRatioDialog
import tomato.gallery.dialogs.ResizeDialog
import tomato.gallery.dialogs.SaveAsDialog
import tomato.gallery.extensions.config
import tomato.gallery.extensions.fixDateTaken
import tomato.gallery.extensions.openEditor
import tomato.gallery.extensions.setupEdgeToEdge
import tomato.gallery.extensions.exifUriForPath
import tomato.gallery.extensions.jpegBytesNearSize
import tomato.gallery.extensions.peekJpegQuality
import tomato.gallery.extensions.saveRotatedImageToFile
import tomato.gallery.extensions.writeExif
import tomato.gallery.helpers.*
import tomato.gallery.models.FilterItem
import com.zomato.photofilters.imageprocessors.Filter
import java.io.*
import java.util.concurrent.Executors
import kotlin.math.max

class EditActivity : SimpleActivity(), CropImageView.OnCropImageCompleteListener {
    companion object {
        init {
            System.loadLibrary("NativeImageProcessor")
        }

        private const val TEMP_FOLDER_NAME = "images"
        private const val ASPECT_X = "aspectX"
        private const val ASPECT_Y = "aspectY"
        private const val CROP = "crop"

        // constants for bottom primary action groups
        private const val PRIMARY_ACTION_NONE = 0
        private const val PRIMARY_ACTION_FILTER = 1
        private const val PRIMARY_ACTION_CROP_ROTATE = 2
        private const val PRIMARY_ACTION_DRAW = 3
        private const val PRIMARY_ACTION_ADJUST = 4
        private const val PRIMARY_ACTION_TEXT = 5

        private const val CROP_ROTATE_NONE = 0
        private const val CROP_ROTATE_ASPECT_RATIO = 1

        private val ENCODABLE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }


    private lateinit var saveUri: Uri
    private var uri: Uri? = null
    private var resizeWidth = 0
    private var resizeHeight = 0
    private var drawColor = 0
    private var lastOtherAspectRatio: Pair<Float, Float>? = null
    private var currPrimaryAction = PRIMARY_ACTION_NONE
    private var currCropRotateAction = CROP_ROTATE_ASPECT_RATIO
    private var currAspectRatio = ASPECT_RATIO_FREE
    private var isCropIntent = false
    private var isEditingWithThirdParty = false
    private var isSharingBitmap = false
    private var wasDrawCanvasPositioned = false
    private var wasOverlayPositioned = false
    private var oldExif: ExifInterface? = null
    private var filterInitialBitmap: Bitmap? = null
    private var originalUri: Uri? = null
    private var workingBitmap: Bitmap? = null
    private val toolUndoStack = ArrayList<Bitmap>(3)
    private var adjustSourceBitmap: Bitmap? = null
    private var adjustPreviewBitmap: Bitmap? = null
    private var overwriteRequested = false
    private var hasUnsavedEdits = false
    private val editorDecodeSize = 4096
    private var trustedRealPath: String? = null
    private val filterPreviewExecutor = Executors.newSingleThreadExecutor()
    @Volatile
    private var filterPreviewGeneration = 0
    private var filterPreviewBase: Bitmap? = null
    private var filterPreviewBaseSource: java.lang.ref.WeakReference<Bitmap>? = null
    private val binding by viewBinding(ActivityEditBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.editorAppBarLayout),
            padBottomSystem = listOf(binding.activityEditHolder)
        )

        setupOptionsMenu()
        handlePermission(getPermissionToRequest()) {
            if (!it) {
                toast(com.simplemobiletools.commons.R.string.no_storage_permissions)
                finish()
                return@handlePermission
            }
            ensureAppUnlocked {
                try {
                    initEditActivity()
                } catch (e: IllegalArgumentException) {
                    toast(R.string.invalid_image_path)
                    finish()
                    return@ensureAppUnlocked
                } catch (e: Exception) {
                    showErrorToast(e)
                    finish()
                    return@ensureAppUnlocked
                }
                onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (hasUnsavedEdits) {
                            ConfirmationDialog(
                                this@EditActivity,
                                "",
                                R.string.discard_unsaved_edits,
                                com.simplemobiletools.commons.R.string.yes,
                                com.simplemobiletools.commons.R.string.no
                            ) {
                                finish()
                            }
                        } else {
                            finish()
                        }
                    }
                })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isEditingWithThirdParty = false
        binding.bottomEditorDrawActions.bottomDrawWidth.setColors(getProperTextColor(), getProperPrimaryColor(), getProperBackgroundColor())
        binding.bottomEditorAdjustActions.adjustBrightness.setColors(getProperTextColor(), getProperPrimaryColor(), getProperBackgroundColor())
        binding.bottomEditorAdjustActions.adjustContrast.setColors(getProperTextColor(), getProperPrimaryColor(), getProperBackgroundColor())
        binding.bottomEditorAdjustActions.adjustSaturation.setColors(getProperTextColor(), getProperPrimaryColor(), getProperBackgroundColor())
        binding.bottomEditorAdjustActions.adjustTemperature.setColors(getProperTextColor(), getProperPrimaryColor(), getProperBackgroundColor())
        binding.bottomEditorTextActions.bottomTextWidth.setColors(getProperTextColor(), getProperPrimaryColor(), getProperBackgroundColor())
        binding.bottomEditorTextActions.bottomTextRotation.setColors(getProperTextColor(), getProperPrimaryColor(), getProperBackgroundColor())
        setupToolbar(binding.editorToolbar, NavigationIcon.Arrow)
        styleEditorToolbar()
    }

    override fun onStop() {
        super.onStop()
        if (isEditingWithThirdParty) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        filterPreviewGeneration++
        filterPreviewExecutor.shutdownNow()
    }

    private fun setupOptionsMenu() {
        styleEditorToolbar()
        binding.editorToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.save_as -> startSaveFlow(overwrite = false)
                R.id.overwrite_original -> startSaveFlow(overwrite = true)
                R.id.undo_last_tool -> undoLastTool()
                R.id.edit -> editWith()
                R.id.share -> shareImage()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun styleEditorToolbar() {
        binding.editorToolbar.apply {
            setTitleTextColor(Color.WHITE)
            overflowIcon = resources.getColoredDrawableWithColor(com.simplemobiletools.commons.R.drawable.ic_three_dots_vector, Color.WHITE)
            navigationIcon = resources.getColoredDrawableWithColor(com.simplemobiletools.commons.R.drawable.ic_arrow_left_vector, Color.WHITE)
        }
        updateMenuItemColors(binding.editorToolbar.menu, forceWhiteIcons = true)
    }

    private fun initEditActivity() {
        if (intent.data == null) {
            toast(R.string.invalid_image_path)
            finish()
            return
        }

        uri = intent.data!!
        originalUri = uri
        if (uri!!.scheme != "file" && uri!!.scheme != "content") {
            toast(R.string.unknown_file_location)
            finish()
            return
        }

        // The editor is exported: REAL_FILE_PATH is honoured only for our own launches,
        // and untrusted callers can't point it at private or PIN-protected files.
        val trusted = intent.isTrustedInternal()
        trustedRealPath = if (trusted) intent.getStringExtra(REAL_FILE_PATH)?.takeIf { it.isNotEmpty() } else null
        if (!trusted && !isAllowedUntrustedInput(uri!!)) {
            toast(R.string.invalid_image_path)
            finish()
            return
        }

        val realPath = trustedRealPath
        if (realPath != null) {
            uri = when {
                isPathOnOTG(realPath) -> uri
                realPath.startsWith("file:/") -> Uri.parse(realPath)
                else -> Uri.fromFile(File(realPath))
            }
        } else {
            (getRealPathFromURI(uri!!))?.apply {
                uri = Uri.fromFile(File(this))
            }
        }

        val extraOutput = if (intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true) intent.extras!!.get(MediaStore.EXTRA_OUTPUT) as? Uri else null
        if (extraOutput != null && !isAllowedOutputUri(extraOutput, trusted)) {
            toast(R.string.unknown_file_location)
            finish()
            return
        }
        saveUri = extraOutput ?: uri!!

        isCropIntent = intent.extras?.get(CROP) == "true"
        if (isCropIntent) {
            binding.bottomEditorPrimaryActions.root.beGone()
            (binding.bottomEditorCropRotateActions.root.layoutParams as RelativeLayout.LayoutParams).addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 1)
        }

        val hideOverwrite = isCropIntent || (intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true) || !canOverwriteInPlace()
        binding.editorToolbar.menu.findItem(R.id.overwrite_original)?.isVisible = !hideOverwrite

        loadDefaultImageView()
        setupBottomActions()

        if (config.lastEditorCropAspectRatio == ASPECT_RATIO_OTHER) {
            if (config.lastEditorCropOtherAspectRatioX == 0f) {
                config.lastEditorCropOtherAspectRatioX = 1f
            }

            if (config.lastEditorCropOtherAspectRatioY == 0f) {
                config.lastEditorCropOtherAspectRatioY = 1f
            }

            lastOtherAspectRatio = Pair(config.lastEditorCropOtherAspectRatioX, config.lastEditorCropOtherAspectRatioY)
        }
        updateAspectRatio(config.lastEditorCropAspectRatio)
        binding.cropImageView.guidelines = CropImageView.Guidelines.ON
        binding.bottomAspectRatios.root.beVisible()
    }

    private fun isOwnAuthority(target: Uri): Boolean {
        val authority = target.authority ?: return false
        return authority == BuildConfig.APPLICATION_ID || authority.startsWith("${BuildConfig.APPLICATION_ID}.")
    }

    private fun isInsideAppPrivateDirs(path: String): Boolean {
        if (path.isEmpty()) {
            return false
        }
        val target = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            return true
        }
        val roots = ArrayList<String>()
        applicationInfo.dataDir?.let { roots.add(it) }
        filesDir?.parentFile?.let { roots.add(it.path) }
        cacheDir?.let { roots.add(it.path) }
        externalCacheDir?.parentFile?.let { roots.add(it.path) }
        getExternalFilesDir(null)?.parentFile?.let { roots.add(it.path) }
        createDeviceProtectedStorageContext().dataDir?.let { roots.add(it.path) }
        return roots.any { root ->
            val canonicalRoot = try {
                File(root).canonicalPath
            } catch (_: Exception) {
                root
            }.trimEnd('/')
            canonicalRoot.isNotEmpty() && (target == canonicalRoot || target.startsWith("$canonicalRoot/"))
        }
    }

    private fun isForbiddenUntrustedPath(path: String): Boolean {
        return isInsideAppPrivateDirs(path) || config.isPathInProtectedFolder(path)
    }

    private fun isAllowedUntrustedInput(input: Uri): Boolean {
        val path = when (input.scheme) {
            "file" -> input.path
            "content" -> {
                if (isOwnAuthority(input)) {
                    return false
                }
                applicationContext.getRealPathFromURI(input)
            }
            else -> null
        }
        return path.isNullOrEmpty() || !isForbiddenUntrustedPath(path)
    }

    private fun isAllowedOutputUri(output: Uri, trusted: Boolean): Boolean {
        if (output.scheme != "content" || isOwnAuthority(output)) {
            return false
        }
        val path = applicationContext.getRealPathFromURI(output)
        if (path.isNullOrEmpty()) {
            return true
        }
        return if (trusted) !isInsideAppPrivateDirs(path) else !isForbiddenUntrustedPath(path)
    }

    private fun isEncodableName(name: String?): Boolean {
        val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return extension in ENCODABLE_EXTENSIONS
    }

    /** Replaces an extension Bitmap.compress can't produce (heic, avif, gif...) with a free `.jpg` name. */
    private fun encodablePath(path: String): String {
        val name = path.substringAfterLast('/')
        if (isEncodableName(name)) {
            return path
        }
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) path.substring(0, path.length - name.length + dot) else path
        var candidate = "$base.jpg"
        var n = 1
        while (File(candidate).exists()) {
            candidate = "${base}_$n.jpg"
            n++
        }
        return candidate
    }

    private fun mimeFormat(mime: String?): CompressFormat? {
        return when (mime?.lowercase()) {
            "image/jpeg", "image/jpg" -> CompressFormat.JPEG
            "image/png" -> CompressFormat.PNG
            "image/webp" -> CompressFormat.WEBP
            else -> null
        }
    }

    private fun contentUriFormat(target: Uri): CompressFormat {
        val name = applicationContext.getFilenameFromContentUri(target)
        if (isEncodableName(name)) {
            return name!!.getCompressionFormat()
        }
        return mimeFormat(contentResolver.getType(target)) ?: CompressFormat.JPEG
    }

    /** Overwrite writes Bitmap.compress output, so only jpg, png and webp targets can be replaced in place. */
    private fun canOverwriteInPlace(): Boolean {
        if (!::saveUri.isInitialized) {
            return false
        }
        val isPlainContent = saveUri.scheme == "content" && applicationContext.getRealPathFromURI(saveUri).isNullOrEmpty()
        val name = if (isPlainContent) {
            applicationContext.getFilenameFromContentUri(saveUri)
        } else {
            overwritePath()?.substringAfterLast('/')
        }
        if (name != null && name.contains('.')) {
            return isEncodableName(name)
        }
        val mime = try {
            contentResolver.getType(saveUri)
        } catch (_: Exception) {
            null
        } ?: return true
        return mimeFormat(mime) != null
    }

    private fun loadDefaultImageView() {
        binding.defaultImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        binding.defaultImageView.beVisible()
        binding.cropImageView.beGone()
        binding.editorDrawCanvas.beGone()
        binding.editorOverlayView.beGone()

        val stacked = usableWorkingBitmap()
        if (stacked != null) {
            binding.defaultImageView.setImageBitmap(stacked)
            if (filterInitialBitmap == null) {
                filterInitialBitmap = stacked
            }
            val currentFilter = getFiltersAdapter()?.getCurrentFilter()
            if (currentFilter != null && currentFilter.name != getString(com.simplemobiletools.commons.R.string.none)) {
                applyFilter(currentFilter)
            }
            if (isCropIntent) {
                binding.bottomEditorPrimaryActions.bottomPrimaryFilter.beGone()
                binding.bottomEditorPrimaryActions.bottomPrimaryDraw.beGone()
                binding.bottomEditorPrimaryActions.bottomPrimaryAdjust.beGone()
                binding.bottomEditorPrimaryActions.bottomPrimaryText.beGone()
            }
            return
        }

        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .set(Downsampler.ALLOW_HARDWARE_CONFIG, false)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .override(editorDecodeSize)

        Glide.with(this)
            .asBitmap()
            .load(uri)
            .apply(options)
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
                    if (uri != originalUri) {
                        uri = originalUri
                        Handler(Looper.getMainLooper()).post {
                            loadDefaultImageView()
                        }
                    }
                    return false
                }

                override fun onResourceReady(
                    bitmap: Bitmap,
                    model: Any,
                    target: Target<Bitmap>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    val copy = mutableArgbCopy(bitmap) ?: bitmap
                    if (workingBitmap == null) {
                        workingBitmap = copy
                    }
                    val currentFilter = getFiltersAdapter()?.getCurrentFilter()
                    if (filterInitialBitmap == null) {
                        filterInitialBitmap = copy
                        loadCropImageView()
                        bottomCropRotateClicked()
                    }

                    if (filterInitialBitmap != null && currentFilter != null && currentFilter.name != getString(com.simplemobiletools.commons.R.string.none)) {
                        binding.defaultImageView.onGlobalLayout {
                            applyFilter(currentFilter)
                        }
                    }

                    if (isCropIntent) {
                        binding.bottomEditorPrimaryActions.bottomPrimaryFilter.beGone()
                        binding.bottomEditorPrimaryActions.bottomPrimaryDraw.beGone()
                        binding.bottomEditorPrimaryActions.bottomPrimaryAdjust.beGone()
                        binding.bottomEditorPrimaryActions.bottomPrimaryText.beGone()
                    }

                    return false
                }
            }).into(binding.defaultImageView)
    }

    private fun loadCropImageView() {
        binding.defaultImageView.beGone()
        binding.editorDrawCanvas.beGone()
        binding.editorOverlayView.beGone()
        binding.cropImageView.apply {
            beVisible()
            scaleType = CropImageView.ScaleType.FIT_CENTER
            setOnCropImageCompleteListener(this@EditActivity)
            guidelines = CropImageView.Guidelines.ON

            if (isCropIntent && shouldCropSquare()) {
                currAspectRatio = ASPECT_RATIO_ONE_ONE
                setFixedAspectRatio(true)
                binding.bottomEditorCropRotateActions.bottomAspectRatio.beGone()
            }

            val applySource = {
                val stacked = usableWorkingBitmap()
                if (stacked != null && (stacked.isRecycled || stacked.width <= 0 || stacked.height <= 0)) {
                    // skip empty bitmaps; CropImageView throws "width must be > 0"
                } else {
                    try {
                        if (stacked != null) {
                            setImageBitmap(stacked)
                        } else {
                            setImageUriAsync(uri)
                        }
                    } catch (_: IllegalArgumentException) {
                    }
                }
            }
            fun applyWhenSized(attempt: Int = 0) {
                if (width > 0 && height > 0) {
                    applySource()
                } else if (attempt < 8) {
                    post { applyWhenSized(attempt + 1) }
                }
            }
            applyWhenSized()
        }
    }

    private fun loadDrawCanvas() {
        binding.defaultImageView.beGone()
        binding.cropImageView.beGone()
        binding.editorOverlayView.beGone()
        binding.editorDrawCanvas.beVisible()

        if (!wasDrawCanvasPositioned) {
            wasDrawCanvasPositioned = true
            resetEditorLayerSize(binding.editorDrawCanvas)
            binding.editorDrawCanvas.onGlobalLayout {
                ensureBackgroundThread {
                    fillCanvasBackground()
                }
            }
        }
    }

    private fun fillCanvasBackground() {
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .set(Downsampler.ALLOW_HARDWARE_CONFIG, false)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .fitCenter()

        try {
            val stacked = usableWorkingBitmap()
            val maxWidth = binding.editorDrawCanvas.width.coerceAtLeast(1)
            val maxHeight = binding.editorDrawCanvas.height.coerceAtLeast(1)
            val bitmap = if (stacked != null) {
                scaleBitmapToFit(stacked, maxWidth, maxHeight)
            } else {
                val builder = Glide.with(applicationContext)
                    .asBitmap()
                    .load(uri)
                    .apply(options)
                    .into(maxWidth, maxHeight)
                builder.get()
            }
            runOnUiThread {
                applyFittedBitmapToLayer(binding.editorDrawCanvas, bitmap) {
                    updateBackgroundBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    private fun loadAdjustView() {
        binding.cropImageView.beGone()
        binding.editorDrawCanvas.beGone()
        binding.editorOverlayView.beGone()
        binding.defaultImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        binding.defaultImageView.beVisible()
        resetAdjustSliders()
        val source = usableWorkingBitmap() ?: filterInitialBitmap
        if (source != null && !source.isRecycled) {
            adjustSourceBitmap = source
            binding.defaultImageView.colorFilter = null
            binding.defaultImageView.setImageBitmap(source)
            applyAdjustPreview()
        } else {
            loadDefaultImageView()
        }
    }

    private fun loadOverlayCanvas() {
        binding.defaultImageView.beGone()
        binding.cropImageView.beGone()
        binding.editorDrawCanvas.beGone()
        binding.editorOverlayView.beVisible()

        if (!wasOverlayPositioned) {
            wasOverlayPositioned = true
            resetEditorLayerSize(binding.editorOverlayView)
            binding.editorOverlayView.onGlobalLayout {
                ensureBackgroundThread {
                    fillOverlayBackground()
                }
            }
        }
    }

    private fun fillOverlayBackground() {
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .set(Downsampler.ALLOW_HARDWARE_CONFIG, false)
            .skipMemoryCache(true)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .fitCenter()

        try {
            val stacked = usableWorkingBitmap()
            val maxWidth = binding.editorOverlayView.width.coerceAtLeast(1)
            val maxHeight = binding.editorOverlayView.height.coerceAtLeast(1)
            val bitmap = if (stacked != null) {
                scaleBitmapToFit(stacked, maxWidth, maxHeight)
            } else {
                Glide.with(applicationContext)
                    .asBitmap()
                    .load(uri)
                    .apply(options)
                    .into(maxWidth, maxHeight)
                    .get()
            }
            runOnUiThread {
                applyFittedBitmapToLayer(binding.editorOverlayView, bitmap) {
                    updateBackgroundBitmap(bitmap)
                }
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun startSaveFlow(overwrite: Boolean) {
        overwriteRequested = overwrite
        if (overwrite && !canOverwriteInPlace()) {
            // e.g. HEIC/AVIF/GIF: never write JPEG bytes under the original name.
            toast(R.string.editor_overwrite_unsupported_format)
            overwriteRequested = false
        }
        setOldExif()
        if (rotationOnlyDegrees() != 0) {
            saveRotationOnly(overwriteRequested)
            return
        }
        when {
            binding.cropImageView.isVisible() -> binding.cropImageView.croppedImageAsync()
            binding.editorDrawCanvas.isVisible() -> saveEditedBitmap(
                if (binding.editorDrawCanvas.hasDrawing()) {
                    binding.editorDrawCanvas.getBitmap(usableWorkingBitmap())
                } else {
                    usableWorkingBitmap() ?: return
                }
            )
            binding.editorOverlayView.isVisible() -> saveEditedBitmap(
                if (binding.editorOverlayView.hasOverlays()) {
                    binding.editorOverlayView.getBitmap(usableWorkingBitmap())
                } else {
                    usableWorkingBitmap() ?: return
                }
            )
            currPrimaryAction == PRIMARY_ACTION_ADJUST -> saveEditedBitmap(currentAdjustBitmap() ?: return)
            else -> saveFilteredImage()
        }
    }

    /**
     * Returns the crop tool's rotation when the only pending edit is a rotation of a
     * local JPEG: no crop, no flip, no resize, no committed tools, not a crop intent.
     * Those saves go through the viewer's rotate path (EXIF or lossless DCT), so the
     * file is not decoded and re-encoded. Returns 0 otherwise.
     */
    private fun rotationOnlyDegrees(): Int {
        // workingBitmap is also set by the initial Glide load, so it does not mean
        // "the user edited something". Committed edits set hasUnsavedEdits or fill
        // the undo stack; only those block the lossless path.
        if (isCropIntent || !binding.cropImageView.isVisible() || hasUnsavedEdits || toolUndoStack.isNotEmpty()) {
            return 0
        }
        if (resizeWidth > 0 || resizeHeight > 0) {
            return 0
        }
        val view = binding.cropImageView
        if (view.isFlippedHorizontally || view.isFlippedVertically) {
            return 0
        }
        val degrees = ((view.rotatedDegrees % 360) + 360) % 360
        if (degrees == 0) {
            return 0
        }
        if (view.cropRect != view.wholeImageRect) {
            return 0
        }
        val path = sourceImagePath() ?: return 0
        return if (path.isJpg()) degrees else 0
    }

    private fun saveRotationOnly(overwrite: Boolean) {
        val degrees = rotationOnlyDegrees()
        val sourcePath = sourceImagePath() ?: return
        val finishSaved: () -> Unit = {
            runOnUiThread {
                setResult(Activity.RESULT_OK, intent)
                hasUnsavedEdits = false
                finish()
            }
        }
        if (overwrite) {
            ensureBackgroundThread {
                saveRotatedImageToFile(sourcePath, sourcePath, degrees, true, finishSaved)
            }
        } else {
            SaveAsDialog(this, sourcePath, false) { newPath ->
                ensureBackgroundThread {
                    saveRotatedImageToFile(sourcePath, newPath, degrees, true, finishSaved)
                }
            }
        }
    }

    private fun saveFilteredImage() {
        val currentFilter = getFiltersAdapter()?.getCurrentFilter()?.filter ?: return
        // Don't clear the views here: the user can still cancel Save-as and keep editing.
        ensureBackgroundThread {
            try {
                val original = getSourceBitmapForFilter()
                currentFilter.processFilter(original)
                runOnUiThread {
                    saveEditedBitmap(original, showSavingToast = true)
                }
            } catch (e: OutOfMemoryError) {
                toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun saveEditedBitmap(bitmap: Bitmap, showSavingToast: Boolean = true) {
        if (overwriteRequested) {
            if (saveUri.scheme == "content" && applicationContext.getRealPathFromURI(saveUri).isNullOrEmpty()) {
                saveBitmapToContentUri(bitmap, saveUri, showSavingToast)
                return
            }
            val path = overwritePath()
            if (path.isNullOrEmpty()) {
                toast(R.string.error_saving_file)
            } else {
                saveBitmapToFile(bitmap, path, showSavingToast)
            }
            return
        }

        if (saveUri.scheme == "file") {
            SaveAsDialog(this, encodablePath(saveUri.path!!), true) {
                saveBitmapToFile(bitmap, encodablePath(it), showSavingToast)
            }
        } else if (saveUri.scheme == "content") {
            val filePathGetter = getNewFilePath()
            SaveAsDialog(this, encodablePath(filePathGetter.first), filePathGetter.second) {
                saveBitmapToFile(bitmap, encodablePath(it), showSavingToast)
            }
        } else {
            toast(R.string.unknown_file_location)
        }
    }

    private fun overwritePath(): String? {
        return when {
            saveUri.scheme == "file" -> saveUri.path
            else -> getNewFilePath().first.takeIf { it.isNotEmpty() }
        }
    }

    private fun saveBitmapToContentUri(bitmap: Bitmap, uri: Uri, showSavingToast: Boolean) {
        if (showSavingToast) {
            toast(com.simplemobiletools.commons.R.string.saving)
        }
        val width = resizeWidth
        val height = resizeHeight
        ensureBackgroundThread {
            try {
                // Encode fully before "wt" truncates the destination.
                val bytes = encodeForSave(bitmap, contentUriFormat(uri), width, height)
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(bytes)
                } ?: throw IOException("Cannot open $uri")
                writeExif(oldExif, uri)
                runOnUiThread {
                    setResult(Activity.RESULT_OK, intent)
                    toast(com.simplemobiletools.commons.R.string.file_saved)
                    finish()
                }
            } catch (e: Throwable) {
                reportSaveFailure(e)
            }
        }
    }

    private fun reportSaveFailure(e: Throwable) {
        when (e) {
            is OutOfMemoryError -> toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
            is Exception -> showErrorToast(e)
            else -> toast(R.string.image_editing_failed)
        }
    }

    private fun encodeForSave(bitmap: Bitmap, format: CompressFormat, width: Int, height: Int): ByteArray {
        val image = if (width > 0 && height > 0) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }
        try {
            val out = ByteArrayOutputStream()
            writeCompressed(image, format, jpegSaveProfile(), out)
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) {
                throw IOException("Encoding failed")
            }
            return bytes
        } finally {
            if (image !== bitmap && !image.isRecycled) {
                image.recycle()
            }
        }
    }

    /** Writes to a sibling temp file, fsyncs it and renames it over [dest], so a failure never leaves a truncated original. */
    private fun writeBytesAtomic(dest: File, bytes: ByteArray): Boolean {
        val parent = dest.parentFile ?: return false
        val dot = dest.name.lastIndexOf('.')
        val suffix = if (dot > 0) dest.name.substring(dot) else ""
        val tmp = File(parent, ".${dest.name}.${System.currentTimeMillis()}.edit$suffix")
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (tmp.length() != bytes.size.toLong() || !tmp.renameTo(dest)) {
                tmp.delete()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            tmp.delete()
            false
        }
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun setOldExif() {
        var inputStream: InputStream? = null
        try {
            if (isNougatPlus()) {
                inputStream = contentResolver.openInputStream(uri!!)
                oldExif = ExifInterface(inputStream!!)
            }
        } catch (e: Exception) {
        } finally {
            inputStream?.close()
        }
    }

    private fun shareImage() {
        // View state (overlay compositing, adapters, visibility) is read on the UI
        // thread; only filtering and encoding run in the background.
        try {
            when {
                currPrimaryAction == PRIMARY_ACTION_ADJUST && binding.defaultImageView.isVisible() -> {
                    ensureBackgroundThread {
                        try {
                            val bitmap = currentAdjustBitmap()
                            if (bitmap == null) {
                                toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                            } else {
                                shareBitmapInBackground(bitmap)
                            }
                        } catch (e: Throwable) {
                            reportSaveFailure(e)
                        }
                    }
                }

                binding.defaultImageView.isVisible() -> {
                    val currentFilter = getFiltersAdapter()?.getCurrentFilter()
                    ensureBackgroundThread {
                        try {
                            val bitmap = if (currentFilter == null) {
                                usableWorkingBitmap() ?: getSourceBitmapForFilter()
                            } else {
                                getSourceBitmapForFilter().also { currentFilter.filter.processFilter(it) }
                            }
                            shareBitmapInBackground(bitmap)
                        } catch (e: Throwable) {
                            reportSaveFailure(e)
                        }
                    }
                }

                binding.cropImageView.isVisible() -> {
                    isSharingBitmap = true
                    binding.cropImageView.croppedImageAsync()
                }

                binding.editorDrawCanvas.isVisible() -> shareBitmap(
                    if (binding.editorDrawCanvas.hasDrawing()) {
                        binding.editorDrawCanvas.getBitmap(usableWorkingBitmap())
                    } else {
                        usableWorkingBitmap() ?: return
                    }
                )

                binding.editorOverlayView.isVisible() -> shareBitmap(
                    if (binding.editorOverlayView.hasOverlays()) {
                        binding.editorOverlayView.getBitmap(usableWorkingBitmap())
                    } else {
                        usableWorkingBitmap() ?: return
                    }
                )
            }
        } catch (e: Throwable) {
            reportSaveFailure(e)
        }
    }

    /** Writes [bitmap] as PNG into the share cache. The name always ends in `.png` to match the data. */
    private fun writeShareTempFile(bitmap: Bitmap): String? {
        val folder = File(cacheDir, TEMP_FOLDER_NAME)
        if (!folder.exists() && !folder.mkdirs()) {
            return null
        }

        val sourceName = sourceImagePath()?.getFilenameFromPath()
            ?: applicationContext.getFilenameFromContentUri(saveUri)
        val base = sourceName?.substringBeforeLast('.')?.takeIf { it.isNotEmpty() && "$it.png".isAValidFilename() }
            ?: "tmp-${System.currentTimeMillis()}"
        val file = File(folder, "$base.png")
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(CompressFormat.PNG, 0, out)) {
                throw IOException("Encoding failed")
            }
        }
        return file.absolutePath
    }

    private fun shareBitmapInBackground(bitmap: Bitmap) {
        val path = writeShareTempFile(bitmap)
        runOnUiThread {
            if (path != null) {
                sharePathIntent(path, BuildConfig.APPLICATION_ID)
            } else {
                toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
            }
        }
    }

    private fun shareBitmap(bitmap: Bitmap) {
        ensureBackgroundThread {
            try {
                shareBitmapInBackground(bitmap)
            } catch (e: Throwable) {
                reportSaveFailure(e)
            }
        }
    }

    private fun getFiltersAdapter() = binding.bottomEditorFilterActions.bottomActionsFilterList.adapter as? FiltersAdapter

    private fun setupBottomActions() {
        setupPrimaryActionButtons()
        setupCropRotateActionButtons()
        setupAspectRatioButtons()
        setupDrawButtons()
        setupAdjustButtons()
        setupTextButtons()
    }

    private fun setupPrimaryActionButtons() {
        binding.bottomEditorPrimaryActions.bottomPrimaryFilter.setOnClickListener {
            bottomFilterClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate.setOnClickListener {
            bottomCropRotateClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimaryDraw.setOnClickListener {
            bottomDrawClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimaryAdjust.setOnClickListener {
            bottomAdjustClicked()
        }

        binding.bottomEditorPrimaryActions.bottomPrimaryText.setOnClickListener {
            bottomTextClicked()
        }
        arrayOf(
            binding.bottomEditorPrimaryActions.bottomPrimaryFilter,
            binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate,
            binding.bottomEditorPrimaryActions.bottomPrimaryDraw,
            binding.bottomEditorPrimaryActions.bottomPrimaryAdjust,
            binding.bottomEditorPrimaryActions.bottomPrimaryText
        ).forEach {
            setupLongPress(it)
        }
    }

    private fun bottomFilterClicked() {
        commitCurrentTool {
            currPrimaryAction = if (currPrimaryAction == PRIMARY_ACTION_FILTER) {
                PRIMARY_ACTION_NONE
            } else {
                PRIMARY_ACTION_FILTER
            }
            updatePrimaryActionButtons()
        }
    }

    private fun bottomCropRotateClicked() {
        commitCurrentTool {
            currPrimaryAction = if (currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE) {
                PRIMARY_ACTION_NONE
            } else {
                PRIMARY_ACTION_CROP_ROTATE
            }
            updatePrimaryActionButtons()
        }
    }

    private fun bottomDrawClicked() {
        commitCurrentTool {
            currPrimaryAction = if (currPrimaryAction == PRIMARY_ACTION_DRAW) {
                PRIMARY_ACTION_NONE
            } else {
                PRIMARY_ACTION_DRAW
            }
            updatePrimaryActionButtons()
        }
    }

    private fun bottomAdjustClicked() {
        commitCurrentTool {
            currPrimaryAction = if (currPrimaryAction == PRIMARY_ACTION_ADJUST) {
                PRIMARY_ACTION_NONE
            } else {
                PRIMARY_ACTION_ADJUST
            }
            updatePrimaryActionButtons()
        }
    }

    private fun bottomTextClicked() {
        commitCurrentTool {
            currPrimaryAction = if (currPrimaryAction == PRIMARY_ACTION_TEXT) {
                PRIMARY_ACTION_NONE
            } else {
                PRIMARY_ACTION_TEXT
            }
            updatePrimaryActionButtons()
        }
    }

    private fun setupCropRotateActionButtons() {
        binding.bottomEditorCropRotateActions.bottomRotate.setOnClickListener {
            binding.cropImageView.rotateImage(90)
        }

        binding.bottomEditorCropRotateActions.bottomResize.beGoneIf(isCropIntent)
        binding.bottomEditorCropRotateActions.bottomResize.setOnClickListener {
            resizeImage()
        }

        binding.bottomEditorCropRotateActions.bottomFlipHorizontally.setOnClickListener {
            binding.cropImageView.flipImageHorizontally()
        }

        binding.bottomEditorCropRotateActions.bottomFlipVertically.setOnClickListener {
            binding.cropImageView.flipImageVertically()
        }

        binding.bottomEditorCropRotateActions.bottomAspectRatio.setOnClickListener {
            currCropRotateAction = if (currCropRotateAction == CROP_ROTATE_ASPECT_RATIO) {
                binding.cropImageView.guidelines = CropImageView.Guidelines.OFF
                binding.bottomAspectRatios.root.beGone()
                CROP_ROTATE_NONE
            } else {
                binding.cropImageView.guidelines = CropImageView.Guidelines.ON
                binding.bottomAspectRatios.root.beVisible()
                CROP_ROTATE_ASPECT_RATIO
            }
            updateCropRotateActionButtons()
        }

        arrayOf(
            binding.bottomEditorCropRotateActions.bottomRotate,
            binding.bottomEditorCropRotateActions.bottomResize,
            binding.bottomEditorCropRotateActions.bottomFlipHorizontally,
            binding.bottomEditorCropRotateActions.bottomFlipVertically,
            binding.bottomEditorCropRotateActions.bottomAspectRatio
        ).forEach {
            setupLongPress(it)
        }
    }

    private fun setupAspectRatioButtons() {
        binding.bottomAspectRatios.bottomAspectRatioFree.setOnClickListener {
            updateAspectRatio(ASPECT_RATIO_FREE)
        }

        binding.bottomAspectRatios.bottomAspectRatioOneOne.setOnClickListener {
            updateAspectRatio(ASPECT_RATIO_ONE_ONE)
        }

        binding.bottomAspectRatios.bottomAspectRatioFourThree.setOnClickListener {
            val next = if (currAspectRatio == ASPECT_RATIO_FOUR_THREE) {
                ASPECT_RATIO_THREE_FOUR
            } else {
                ASPECT_RATIO_FOUR_THREE
            }
            updateAspectRatio(next)
        }

        binding.bottomAspectRatios.bottomAspectRatioSixteenNine.setOnClickListener {
            val next = if (currAspectRatio == ASPECT_RATIO_SIXTEEN_NINE) {
                ASPECT_RATIO_NINE_SIXTEEN
            } else {
                ASPECT_RATIO_SIXTEEN_NINE
            }
            updateAspectRatio(next)
        }

        binding.bottomAspectRatios.bottomAspectRatioOther.setOnClickListener {
            OtherAspectRatioDialog(this, lastOtherAspectRatio) {
                lastOtherAspectRatio = it
                config.lastEditorCropOtherAspectRatioX = it.first
                config.lastEditorCropOtherAspectRatioY = it.second
                updateAspectRatio(ASPECT_RATIO_OTHER)
            }
        }

        updateAspectRatioButtons()
    }

    private fun setupDrawButtons() {
        updateDrawColor(config.lastEditorDrawColor)
        binding.bottomEditorDrawActions.bottomDrawWidth.progress = config.lastEditorBrushSize
        updateBrushSize(config.lastEditorBrushSize)

        binding.bottomEditorDrawActions.bottomDrawColorClickable.setOnClickListener {
            ColorPickerDialog(this, drawColor) { wasPositivePressed, color ->
                if (wasPositivePressed) {
                    updateDrawColor(color)
                }
            }
        }

        binding.bottomEditorDrawActions.bottomDrawWidth.onSeekBarChangeListener {
            config.lastEditorBrushSize = it
            updateBrushSize(it)
        }

        binding.bottomEditorDrawActions.bottomDrawUndo.setOnClickListener {
            binding.editorDrawCanvas.undo()
        }
    }

    private fun setupAdjustButtons() {
        resetAdjustSliders()
        val listener: (Int) -> Unit = { applyAdjustPreview() }
        binding.bottomEditorAdjustActions.adjustBrightness.onSeekBarChangeListener(listener)
        binding.bottomEditorAdjustActions.adjustContrast.onSeekBarChangeListener(listener)
        binding.bottomEditorAdjustActions.adjustSaturation.onSeekBarChangeListener(listener)
        binding.bottomEditorAdjustActions.adjustTemperature.onSeekBarChangeListener(listener)
    }

    private fun resetAdjustSliders() {
        binding.bottomEditorAdjustActions.adjustBrightness.progress = EditorColorMatrix.SLIDER_CENTER
        binding.bottomEditorAdjustActions.adjustContrast.progress = EditorColorMatrix.SLIDER_CENTER
        binding.bottomEditorAdjustActions.adjustSaturation.progress = EditorColorMatrix.SLIDER_CENTER
        binding.bottomEditorAdjustActions.adjustTemperature.progress = EditorColorMatrix.SLIDER_CENTER
        recycleAdjustPreview()
    }

    private fun applyAdjustPreview() {
        val source = adjustSourceBitmap ?: usableWorkingBitmap() ?: filterInitialBitmap ?: return
        if (source.isRecycled) {
            return
        }
        val brightness = binding.bottomEditorAdjustActions.adjustBrightness.progress
        val contrast = binding.bottomEditorAdjustActions.adjustContrast.progress
        val saturation = binding.bottomEditorAdjustActions.adjustSaturation.progress
        val temperature = binding.bottomEditorAdjustActions.adjustTemperature.progress
        binding.defaultImageView.setImageBitmap(source)
        if (EditorColorMatrix.isIdentity(brightness, contrast, saturation, temperature)) {
            binding.defaultImageView.colorFilter = null
        } else {
            binding.defaultImageView.colorFilter = ColorMatrixColorFilter(
                EditorColorMatrix.matrix(brightness, contrast, saturation, temperature)
            )
        }
    }

    private fun currentAdjustBitmap(): Bitmap? {
        val source = adjustSourceBitmap ?: usableWorkingBitmap() ?: filterInitialBitmap ?: return null
        if (source.isRecycled) {
            return null
        }
        val brightness = binding.bottomEditorAdjustActions.adjustBrightness.progress
        val contrast = binding.bottomEditorAdjustActions.adjustContrast.progress
        val saturation = binding.bottomEditorAdjustActions.adjustSaturation.progress
        val temperature = binding.bottomEditorAdjustActions.adjustTemperature.progress
        if (EditorColorMatrix.isIdentity(brightness, contrast, saturation, temperature)) {
            return source
        }
        return EditorColorMatrix.apply(source, brightness, contrast, saturation, temperature)
    }

    private fun recycleAdjustPreview() {
        val preview = adjustPreviewBitmap
        adjustPreviewBitmap = null
        if (preview != null && preview !== adjustSourceBitmap && preview !== workingBitmap && preview !== filterInitialBitmap && !preview.isRecycled) {
            preview.recycle()
        }
    }

    private fun setupTextButtons() {
        updateTextColor(config.lastEditorDrawColor)
        updateTextFont(config.lastEditorTextFont)
        binding.bottomEditorTextActions.bottomTextWidth.progress = 40
        binding.editorOverlayView.applySizeToSelected(40)
        binding.bottomEditorTextActions.bottomTextRotation.progress = 0
        binding.editorOverlayView.onOverlayChanged = { rotation ->
            if (binding.bottomEditorTextActions.bottomTextRotation.progress != rotation) {
                binding.bottomEditorTextActions.bottomTextRotation.progress = rotation
            }
        }
        binding.editorOverlayView.onEditSelectedOverlay = { current ->
            AddTextDialog(this, current) { text ->
                binding.editorOverlayView.updateSelectedText(text)
            }
        }

        binding.bottomEditorTextActions.bottomTextAdd.setOnClickListener {
            AddTextDialog(this) { text ->
                val rotation = binding.bottomEditorTextActions.bottomTextRotation.progress
                binding.editorOverlayView.addLabel(text)
                binding.editorOverlayView.applyRotationToSelected(rotation)
                binding.bottomEditorTextActions.bottomTextRotation.progress = rotation
            }
        }

        binding.bottomEditorTextActions.bottomTextSticker.setOnClickListener {
            AddStickerDialog(this) { emoji ->
                val rotation = binding.bottomEditorTextActions.bottomTextRotation.progress
                binding.editorOverlayView.addLabel(emoji)
                binding.editorOverlayView.applyRotationToSelected(rotation)
                binding.bottomEditorTextActions.bottomTextRotation.progress = rotation
            }
        }

        binding.bottomEditorTextActions.bottomTextFont.setOnClickListener {
            FontPickerDialog(this, binding.editorOverlayView.overlayFontId) { fontId ->
                updateTextFont(fontId)
            }
        }

        binding.bottomEditorTextActions.bottomTextWidth.onSeekBarChangeListener {
            binding.editorOverlayView.applySizeToSelected(it)
        }

        binding.bottomEditorTextActions.bottomTextRotation.onSeekBarChangeListener {
            binding.editorOverlayView.applyRotationToSelected(it)
        }

        binding.bottomEditorTextActions.bottomTextColor.setOnClickListener {
            ColorPickerDialog(this, binding.editorOverlayView.overlayColor) { wasPositivePressed, color ->
                if (wasPositivePressed) {
                    updateTextColor(color)
                }
            }
        }

        binding.bottomEditorTextActions.bottomTextUndo.setOnClickListener {
            binding.editorOverlayView.undo()
        }
    }

    private fun updateTextColor(color: Int) {
        binding.editorOverlayView.applyColorToSelected(color)
        binding.bottomEditorTextActions.bottomTextColor.applyColorFilter(color)
    }

    private fun updateTextFont(fontId: Int) {
        config.lastEditorTextFont = fontId
        binding.editorOverlayView.applyFontToSelected(fontId)
    }

    private fun updateBrushSize(percent: Int) {
        binding.editorDrawCanvas.updateBrushSize(percent)
        val scale = max(0.03f, percent / 100f)
        binding.bottomEditorDrawActions.bottomDrawColor.scaleX = scale
        binding.bottomEditorDrawActions.bottomDrawColor.scaleY = scale
    }

    private fun syncEditorPanel(panel: View, show: Boolean, anchor: View) {
        if (!show) {
            if (!panel.isVisible()) return
            if (FluidMotion.isReducedMotion(this)) {
                panel.beGone()
                panel.translationY = 0f
                return
            }
            val travel = panel.height.toFloat().coerceAtLeast(1f)
            val anim = FluidMotion.spring(panel, DynamicAnimation.TRANSLATION_Y, travel, 1f, 0.3f)
            if (anim == null) {
                panel.beGone()
                return
            }
            lateinit var listener: DynamicAnimation.OnAnimationEndListener
            listener = DynamicAnimation.OnAnimationEndListener { animation, canceled, _, _ ->
                animation.removeEndListener(listener)
                if (!canceled && panel.translationY >= travel * 0.8f) panel.beGone()
            }
            anim.addEndListener(listener)
            return
        }
        if (panel.isVisible() && panel.translationY == 0f && panel.scaleX == 1f) return
        panel.beVisible()
        panel.post {
            if (!panel.isVisible()) return@post
            if (FluidMotion.isReducedMotion(this)) {
                panel.translationY = 0f
                panel.scaleX = 1f
                panel.scaleY = 1f
                return@post
            }
            val travel = panel.height.toFloat().coerceAtLeast(1f)
            panel.pivotY = panel.height.toFloat()
            val anchorLoc = IntArray(2)
            val panelLoc = IntArray(2)
            anchor.getLocationOnScreen(anchorLoc)
            panel.getLocationOnScreen(panelLoc)
            panel.pivotX = (anchorLoc[0] + anchor.width / 2f - panelLoc[0]).coerceIn(0f, panel.width.toFloat())
            panel.translationY = travel
            panel.scaleX = 0.96f
            panel.scaleY = 0.96f
            FluidMotion.spring(panel, DynamicAnimation.TRANSLATION_Y, 0f, 1f, 0.35f)
            FluidMotion.spring(panel, DynamicAnimation.SCALE_X, 1f, 1f, 0.35f)
            FluidMotion.spring(panel, DynamicAnimation.SCALE_Y, 1f, 1f, 0.35f)
        }
    }

    private fun updatePrimaryActionButtons() {
        if (binding.cropImageView.isGone() && currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE) {
            loadCropImageView()
        } else if (currPrimaryAction == PRIMARY_ACTION_ADJUST) {
            loadAdjustView()
        } else if (binding.defaultImageView.isGone() && currPrimaryAction == PRIMARY_ACTION_FILTER) {
            loadDefaultImageView()
        } else if (binding.editorDrawCanvas.isGone() && currPrimaryAction == PRIMARY_ACTION_DRAW) {
            loadDrawCanvas()
        } else if (binding.editorOverlayView.isGone() && currPrimaryAction == PRIMARY_ACTION_TEXT) {
            loadOverlayCanvas()
        }

        arrayOf(
            binding.bottomEditorPrimaryActions.bottomPrimaryFilter,
            binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate,
            binding.bottomEditorPrimaryActions.bottomPrimaryDraw,
            binding.bottomEditorPrimaryActions.bottomPrimaryAdjust,
            binding.bottomEditorPrimaryActions.bottomPrimaryText
        ).forEach {
            it.applyColorFilter(Color.WHITE)
        }

        val currentPrimaryActionButton = when (currPrimaryAction) {
            PRIMARY_ACTION_FILTER -> binding.bottomEditorPrimaryActions.bottomPrimaryFilter
            PRIMARY_ACTION_CROP_ROTATE -> binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate
            PRIMARY_ACTION_DRAW -> binding.bottomEditorPrimaryActions.bottomPrimaryDraw
            PRIMARY_ACTION_ADJUST -> binding.bottomEditorPrimaryActions.bottomPrimaryAdjust
            PRIMARY_ACTION_TEXT -> binding.bottomEditorPrimaryActions.bottomPrimaryText
            else -> null
        }

        currentPrimaryActionButton?.applyColorFilter(getProperPrimaryColor())
        syncEditorPanel(binding.bottomEditorFilterActions.root, currPrimaryAction == PRIMARY_ACTION_FILTER, binding.bottomEditorPrimaryActions.bottomPrimaryFilter)
        syncEditorPanel(binding.bottomEditorCropRotateActions.root, currPrimaryAction == PRIMARY_ACTION_CROP_ROTATE, binding.bottomEditorPrimaryActions.bottomPrimaryCropRotate)
        syncEditorPanel(binding.bottomEditorDrawActions.root, currPrimaryAction == PRIMARY_ACTION_DRAW, binding.bottomEditorPrimaryActions.bottomPrimaryDraw)
        syncEditorPanel(binding.bottomEditorAdjustActions.root, currPrimaryAction == PRIMARY_ACTION_ADJUST, binding.bottomEditorPrimaryActions.bottomPrimaryAdjust)
        syncEditorPanel(binding.bottomEditorTextActions.root, currPrimaryAction == PRIMARY_ACTION_TEXT, binding.bottomEditorPrimaryActions.bottomPrimaryText)

        if (currPrimaryAction == PRIMARY_ACTION_FILTER && binding.bottomEditorFilterActions.bottomActionsFilterList.adapter == null) {
            ensureBackgroundThread {
                val thumbnailSize = resources.getDimension(R.dimen.bottom_filters_thumbnail_size).toInt().coerceAtLeast(1)

                val bitmap = try {
                    val stacked = usableWorkingBitmap()
                    if (stacked != null && stacked.width > 0 && stacked.height > 0) {
                        Bitmap.createScaledBitmap(stacked, thumbnailSize, thumbnailSize, true)
                    } else {
                        Glide.with(this)
                            .asBitmap()
                            .load(uri).listener(object : RequestListener<Bitmap> {
                                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>, isFirstResource: Boolean): Boolean {
                                    showErrorToast(e.toString())
                                    return false
                                }

                                override fun onResourceReady(
                                    resource: Bitmap,
                                    model: Any,
                                    target: Target<Bitmap>,
                                    dataSource: DataSource,
                                    isFirstResource: Boolean
                                ) = false
                            })
                            .submit(thumbnailSize, thumbnailSize)
                            .get()
                    }
                } catch (e: GlideException) {
                    showErrorToast(e)
                    finish()
                    return@ensureBackgroundThread
                }

                runOnUiThread {
                    val filterThumbnailsManager = FilterThumbnailsManager()
                    filterThumbnailsManager.clearThumbs()

                    val noneLabel = getString(com.simplemobiletools.commons.R.string.none)
                    filterThumbnailsManager.addThumb(FilterItem(bitmap, Filter(), noneLabel))

                    FilterPack.getFilterPack(this).forEach {
                        filterThumbnailsManager.addThumb(FilterItem(bitmap, it.filter, it.name))
                    }

                    val filterItems = filterThumbnailsManager.processThumbs()
                    val adapter = FiltersAdapter(applicationContext, filterItems) {
                        val layoutManager = binding.bottomEditorFilterActions.bottomActionsFilterList.layoutManager as LinearLayoutManager
                        applyFilter(filterItems[it])

                        if (it == layoutManager.findLastCompletelyVisibleItemPosition() || it == layoutManager.findLastVisibleItemPosition()) {
                            binding.bottomEditorFilterActions.bottomActionsFilterList.smoothScrollBy(thumbnailSize, 0)
                        } else if (it == layoutManager.findFirstCompletelyVisibleItemPosition() || it == layoutManager.findFirstVisibleItemPosition()) {
                            binding.bottomEditorFilterActions.bottomActionsFilterList.smoothScrollBy(-thumbnailSize, 0)
                        }
                    }

                    binding.bottomEditorFilterActions.bottomActionsFilterList.adapter = adapter
                    adapter.notifyDataSetChanged()
                }
            }
        }

        if (currPrimaryAction != PRIMARY_ACTION_CROP_ROTATE) {
            binding.bottomAspectRatios.root.beGone()
            currCropRotateAction = CROP_ROTATE_NONE
        }
        updateCropRotateActionButtons()
    }

    /**
     * Filter preview: runs on a screen-sized copy on a single background thread.
     * Saving still filters the full-size bitmap.
     */
    private fun applyFilter(filterItem: FilterItem) {
        val source = filterInitialBitmap ?: return
        if (source.isRecycled) {
            return
        }
        val generation = ++filterPreviewGeneration
        val maxWidth = binding.defaultImageView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val maxHeight = binding.defaultImageView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        try {
            filterPreviewExecutor.execute {
                if (generation != filterPreviewGeneration) {
                    return@execute
                }
                try {
                    val base = filterPreviewBaseFor(source, maxWidth, maxHeight) ?: return@execute
                    val preview = base.copy(Bitmap.Config.ARGB_8888, true) ?: return@execute
                    val filtered = filterItem.filter.processFilter(preview)
                    runOnUiThread {
                        if (generation == filterPreviewGeneration && !isDestroyed && !filtered.isRecycled) {
                            binding.defaultImageView.setImageBitmap(filtered)
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
                } catch (e: Exception) {
                    showErrorToast(e)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Called only on [filterPreviewExecutor]. */
    private fun filterPreviewBaseFor(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap? {
        val cached = filterPreviewBase
        if (filterPreviewBaseSource?.get() === source && cached != null && !cached.isRecycled) {
            return cached
        }
        if (source.isRecycled) {
            return null
        }
        // Never cache the editor's own bitmap: copy it so later recycles can't reach the preview.
        val base = if (source.width <= maxWidth && source.height <= maxHeight) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            scaleBitmapToFit(source, maxWidth, maxHeight).let { if (it === source) source.copy(Bitmap.Config.ARGB_8888, false) else it }
        } ?: return null
        filterPreviewBase = base
        filterPreviewBaseSource = java.lang.ref.WeakReference(source)
        return base
    }

    private fun updateAspectRatio(aspectRatio: Int) {
        currAspectRatio = aspectRatio
        config.lastEditorCropAspectRatio = aspectRatio
        updateAspectRatioButtons()

        binding.cropImageView.apply {
            if (aspectRatio == ASPECT_RATIO_FREE) {
                setFixedAspectRatio(false)
            } else {
                val newAspectRatio = when (aspectRatio) {
                    ASPECT_RATIO_ONE_ONE -> Pair(1f, 1f)
                    ASPECT_RATIO_FOUR_THREE -> Pair(4f, 3f)
                    ASPECT_RATIO_THREE_FOUR -> Pair(3f, 4f)
                    ASPECT_RATIO_SIXTEEN_NINE -> Pair(16f, 9f)
                    ASPECT_RATIO_NINE_SIXTEEN -> Pair(9f, 16f)
                    else -> Pair(lastOtherAspectRatio!!.first, lastOtherAspectRatio!!.second)
                }

                setAspectRatio(
                    newAspectRatio.first.toInt().coerceAtLeast(1),
                    newAspectRatio.second.toInt().coerceAtLeast(1)
                )
            }
        }
    }

    private fun updateAspectRatioButtons() {
        arrayOf(
            binding.bottomAspectRatios.bottomAspectRatioFree,
            binding.bottomAspectRatios.bottomAspectRatioOneOne,
            binding.bottomAspectRatios.bottomAspectRatioFourThree,
            binding.bottomAspectRatios.bottomAspectRatioSixteenNine,
            binding.bottomAspectRatios.bottomAspectRatioOther,
        ).forEach {
            it.setTextColor(Color.WHITE)
        }

        binding.bottomAspectRatios.bottomAspectRatioFourThree.text =
            if (currAspectRatio == ASPECT_RATIO_THREE_FOUR) "3:4" else "4:3"
        binding.bottomAspectRatios.bottomAspectRatioSixteenNine.text =
            if (currAspectRatio == ASPECT_RATIO_NINE_SIXTEEN) "9:16" else "16:9"

        val currentAspectRatioButton = when (currAspectRatio) {
            ASPECT_RATIO_FREE -> binding.bottomAspectRatios.bottomAspectRatioFree
            ASPECT_RATIO_ONE_ONE -> binding.bottomAspectRatios.bottomAspectRatioOneOne
            ASPECT_RATIO_FOUR_THREE, ASPECT_RATIO_THREE_FOUR -> binding.bottomAspectRatios.bottomAspectRatioFourThree
            ASPECT_RATIO_SIXTEEN_NINE, ASPECT_RATIO_NINE_SIXTEEN -> binding.bottomAspectRatios.bottomAspectRatioSixteenNine
            else -> binding.bottomAspectRatios.bottomAspectRatioOther
        }

        currentAspectRatioButton.setTextColor(getProperPrimaryColor())
    }

    private fun updateCropRotateActionButtons() {
        arrayOf(binding.bottomEditorCropRotateActions.bottomAspectRatio).forEach {
            it.applyColorFilter(Color.WHITE)
        }

        val primaryActionView = when (currCropRotateAction) {
            CROP_ROTATE_ASPECT_RATIO -> binding.bottomEditorCropRotateActions.bottomAspectRatio
            else -> null
        }

        primaryActionView?.applyColorFilter(getProperPrimaryColor())
    }

    private fun updateDrawColor(color: Int) {
        drawColor = color
        binding.bottomEditorDrawActions.bottomDrawColor.applyColorFilter(color)
        config.lastEditorDrawColor = color
        binding.editorDrawCanvas.updateColor(color)
    }

    private fun resizeImage() {
        val point = getAreaSize()
        if (point == null) {
            toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
            return
        }

        ResizeDialog(this, point) {
            resizeWidth = it.x
            resizeHeight = it.y
            binding.cropImageView.croppedImageAsync()
        }
    }

    private fun shouldCropSquare(): Boolean {
        val extras = intent.extras
        return if (extras != null && extras.containsKey(ASPECT_X) && extras.containsKey(ASPECT_Y)) {
            extras.getInt(ASPECT_X) == extras.getInt(ASPECT_Y)
        } else {
            false
        }
    }

    private fun getAreaSize(): Point? {
        val rect = binding.cropImageView.cropRect ?: return null
        val rotation = binding.cropImageView.rotatedDegrees
        return if (rotation == 0 || rotation == 180) {
            Point(rect.width(), rect.height())
        } else {
            Point(rect.height(), rect.width())
        }
    }

    override fun onCropImageComplete(view: CropImageView, result: CropImageView.CropResult) {
        if (result.error == null && result.bitmap != null) {
            setOldExif()

            val bitmap = result.bitmap!!
            if (isSharingBitmap) {
                isSharingBitmap = false
                shareBitmap(bitmap)
                return
            }

            if (isCropIntent) {
                if (saveUri.scheme == "file") {
                    saveBitmapToFile(bitmap, encodablePath(saveUri.path!!), true)
                } else {
                    val target = saveUri
                    ensureBackgroundThread {
                        try {
                            // Encode first, then truncate the caller's output.
                            val profile = jpegSaveProfile()
                            val bytes = jpegBytesNearSize(bitmap, profile.targetBytesFor(bitmap), profile.quality)
                            contentResolver.openOutputStream(target, "wt")?.use { out ->
                                out.write(bytes)
                            } ?: throw IOException("Cannot open $target")
                            writeExif(oldExif, target)
                            runOnUiThread {
                                Intent().apply {
                                    data = target
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    setResult(RESULT_OK, this)
                                }
                                finish()
                            }
                        } catch (e: Throwable) {
                            reportSaveFailure(e)
                        }
                    }
                }
            } else {
                saveEditedBitmap(bitmap)
            }
        } else {
            toast("${getString(R.string.image_editing_failed)}: ${result.error?.message}")
        }
    }

    private fun getNewFilePath(): Pair<String, Boolean> {
        var newPath = applicationContext.getRealPathFromURI(saveUri) ?: ""
        if (newPath.startsWith("/mnt/") || isInsideAppPrivateDirs(newPath)) {
            newPath = ""
        }

        var shouldAppendFilename = true
        if (newPath.isEmpty()) {
            val filename = applicationContext.getFilenameFromContentUri(saveUri)?.takeIf { it.isAValidFilename() } ?: ""
            if (filename.isNotEmpty()) {
                val path = trustedRealPath?.getParentPath() ?: internalStoragePath
                newPath = "$path/$filename"
                shouldAppendFilename = false
            }
        }

        if (newPath.isEmpty()) {
            newPath = "$internalStoragePath/${getCurrentFormattedDateTime()}.${saveUri.toString().getFilenameExtension()}"
            shouldAppendFilename = false
        }

        return Pair(newPath, shouldAppendFilename)
    }

    private fun saveBitmapToFile(bitmap: Bitmap, path: String, showSavingToast: Boolean) {
        if (showSavingToast) {
            toast(com.simplemobiletools.commons.R.string.saving)
        }
        val width = resizeWidth
        val height = resizeHeight
        ensureBackgroundThread {
            try {
                // Encode fully before touching the destination, so an OOM or encoder
                // failure can't leave the original truncated.
                val bytes = encodeForSave(bitmap, path.getCompressionFormat(), width, height)
                val file = File(path)
                if (writeBytesAtomic(file, bytes)) {
                    onBitmapFileWritten(file.absolutePath)
                } else {
                    // SAF / scoped-storage fallback: not atomic, but the bytes are ready,
                    // so the window between truncate and write is a single write().
                    val fileDirItem = FileDirItem(path, path.getFilenameFromPath())
                    getFileOutputStream(fileDirItem, true) { out ->
                        if (out == null) {
                            toast(R.string.image_editing_failed)
                            return@getFileOutputStream
                        }
                        ensureBackgroundThread {
                            try {
                                out.use { it.write(bytes) }
                                onBitmapFileWritten(file.absolutePath)
                            } catch (e: Throwable) {
                                reportSaveFailure(e)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                reportSaveFailure(e)
            }
        }
    }

    private fun onBitmapFileWritten(path: String) {
        writeExif(oldExif, exifUriForPath(path))
        runOnUiThread {
            setResult(Activity.RESULT_OK, intent)
            scanFinalPath(path)
        }
    }

    private fun sourceImagePath(): String? {
        uri?.takeIf { it.scheme == "file" }?.path?.takeIf { it.isNotEmpty() }?.let { return it }
        trustedRealPath?.let { return it }
        return uri?.let { applicationContext.getRealPathFromURI(it) }?.takeIf { it.isNotEmpty() }
    }

    private fun jpegSaveProfile(): JpegSaveProfile {
        val path = sourceImagePath() ?: return JpegSaveProfile(0L, null, 0L)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            android.graphics.BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
        }
        val srcPixels = if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth.toLong() * options.outHeight.toLong()
        } else {
            0L
        }
        return JpegSaveProfile(File(path).length(), peekJpegQuality(path), srcPixels)
    }

    private fun writeCompressed(bitmap: Bitmap, format: CompressFormat, profile: JpegSaveProfile, out: OutputStream) {
        if (format == CompressFormat.JPEG) {
            out.write(jpegBytesNearSize(bitmap, profile.targetBytesFor(bitmap), profile.quality))
        } else {
            bitmap.compress(format, 90, out)
        }
    }

    private data class JpegSaveProfile(val targetBytes: Long, val quality: Int?, val srcPixels: Long) {
        /** Scales the source size to the output pixel count, so a crop targets a proportionally smaller file. */
        fun targetBytesFor(bitmap: Bitmap): Long {
            if (targetBytes <= 0L || srcPixels <= 0L) {
                return targetBytes
            }
            val outPixels = bitmap.width.toLong() * bitmap.height.toLong()
            return targetBytes * outPixels / srcPixels
        }
    }

    private fun editWith() {
        openEditor(uri.toString(), true)
        isEditingWithThirdParty = true
    }

    private fun scanFinalPath(path: String) {
        val paths = arrayListOf(path)
        rescanPaths(paths) {
            fixDateTaken(paths, false)
            setResult(Activity.RESULT_OK, intent)
            toast(com.simplemobiletools.commons.R.string.file_saved)
            finish()
        }
    }

    private fun setupLongPress(view: ImageView) {
        view.setOnLongClickListener {
            val contentDescription = view.contentDescription
            if (contentDescription != null) {
                toast(contentDescription.toString())
            }
            true
        }
    }

    private fun usableWorkingBitmap(): Bitmap? {
        val bitmap = workingBitmap
        return if (bitmap != null && !bitmap.isRecycled) bitmap else null
    }

    private fun mutableArgbCopy(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled) {
            return null
        }
        return try {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun getSourceBitmapForFilter(): Bitmap {
        val stacked = usableWorkingBitmap()
        if (stacked != null) {
            return mutableArgbCopy(stacked) ?: stacked
        }
        return Glide.with(applicationContext)
            .asBitmap()
            .load(uri)
            .apply(
                RequestOptions()
                    .format(DecodeFormat.PREFER_ARGB_8888)
                    .set(Downsampler.ALLOW_HARDWARE_CONFIG, false)
            )
            .submit(editorDecodeSize, editorDecodeSize)
            .get()
    }

    private fun scaleBitmapToFit(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = maxWidth.coerceAtLeast(1)
        val height = maxHeight.coerceAtLeast(1)
        val scale = minOf(
            width.toFloat() / source.width.coerceAtLeast(1),
            height.toFloat() / source.height.coerceAtLeast(1)
        )
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        if (targetWidth == source.width && targetHeight == source.height) {
            return source
        }
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun resetEditorLayerSize(view: View) {
        val params = view.layoutParams as RelativeLayout.LayoutParams
        params.width = RelativeLayout.LayoutParams.MATCH_PARENT
        params.height = RelativeLayout.LayoutParams.MATCH_PARENT
        view.layoutParams = params
        view.y = 0f
    }

    private fun <T : View> applyFittedBitmapToLayer(view: T, bitmap: Bitmap, bind: T.() -> Unit) {
        view.bind()
        val params = view.layoutParams as RelativeLayout.LayoutParams
        params.width = bitmap.width
        params.height = bitmap.height
        params.addRule(RelativeLayout.CENTER_HORIZONTAL)
        view.layoutParams = params
        val parentHeight = (view.parent as? View)?.height ?: view.height
        view.y = ((parentHeight - bitmap.height) / 2f).coerceAtLeast(0f)
        view.requestLayout()
    }

    private fun replaceWorkingBitmap(bitmap: Bitmap, recordUndo: Boolean = true) {
        val previous = workingBitmap
        var pushedPrevious = false
        if (recordUndo && previous != null && previous !== bitmap && !previous.isRecycled) {
            // The previous bitmap is no longer the working copy, so keep it as-is
            // instead of duplicating ~64 MB per edit.
            while (toolUndoStack.size >= 3) {
                val oldest = toolUndoStack.removeAt(0)
                if (!oldest.isRecycled && oldest !== bitmap && oldest !== filterInitialBitmap && oldest !== previous) {
                    oldest.recycle()
                }
            }
            toolUndoStack.add(previous)
            pushedPrevious = true
        }
        workingBitmap = bitmap
        hasUnsavedEdits = true
        if (!pushedPrevious && previous != null && previous !== bitmap && previous !== filterInitialBitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    private fun undoLastTool() {
        if (toolUndoStack.isEmpty()) {
            toast(R.string.nothing_to_undo)
            return
        }
        val restored = toolUndoStack.removeAt(toolUndoStack.lastIndex)
        replaceWorkingBitmap(restored, recordUndo = false)
        invalidateToolCaches()
        currPrimaryAction = PRIMARY_ACTION_NONE
        updatePrimaryActionButtons()
        loadDefaultImageView()
    }

    private fun invalidateToolCaches() {
        filterInitialBitmap = workingBitmap
        binding.bottomEditorFilterActions.bottomActionsFilterList.adapter = null
        wasDrawCanvasPositioned = false
        wasOverlayPositioned = false
        binding.editorDrawCanvas.clearDrawing()
        binding.editorOverlayView.clearOverlays()
        resetEditorLayerSize(binding.editorDrawCanvas)
        resetEditorLayerSize(binding.editorOverlayView)
        recycleAdjustPreview()
        adjustSourceBitmap = null
        binding.defaultImageView.colorFilter = null
    }

    private fun commitCurrentTool(done: () -> Unit) {
        try {
            when {
                binding.cropImageView.isVisible() -> {
                    ensureBackgroundThread {
                        try {
                            val cropped = binding.cropImageView.getCroppedImage()
                            runOnUiThread {
                                if (cropped != null) {
                                    replaceWorkingBitmap(cropped)
                                    invalidateToolCaches()
                                }
                                done()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                showErrorToast(e)
                                done()
                            }
                        }
                    }
                    return
                }
                binding.editorDrawCanvas.isVisible() && wasDrawCanvasPositioned && binding.editorDrawCanvas.hasDrawing() -> {
                    replaceWorkingBitmap(binding.editorDrawCanvas.getBitmap(usableWorkingBitmap()))
                    invalidateToolCaches()
                }
                binding.editorOverlayView.isVisible() && wasOverlayPositioned -> {
                    if (binding.editorOverlayView.hasOverlays()) {
                        replaceWorkingBitmap(binding.editorOverlayView.getBitmap(usableWorkingBitmap()))
                        invalidateToolCaches()
                    }
                }
                currPrimaryAction == PRIMARY_ACTION_ADJUST -> {
                    ensureBackgroundThread {
                        try {
                            val adjusted = currentAdjustBitmap()
                            val keep = if (adjusted != null && !adjusted.isRecycled) {
                                if (adjusted === adjustSourceBitmap || adjusted === workingBitmap || adjusted === filterInitialBitmap) {
                                    adjusted.copy(Bitmap.Config.ARGB_8888, true)
                                } else {
                                    adjusted
                                }
                            } else {
                                null
                            }
                            runOnUiThread {
                                if (keep != null) {
                                    adjustPreviewBitmap = null
                                    replaceWorkingBitmap(keep)
                                    invalidateToolCaches()
                                }
                                done()
                            }
                        } catch (e: OutOfMemoryError) {
                            runOnUiThread {
                                toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
                                done()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                showErrorToast(e)
                                done()
                            }
                        }
                    }
                    return
                }
                binding.defaultImageView.isVisible() -> {
                    val currentFilter = getFiltersAdapter()?.getCurrentFilter()
                    val source = usableWorkingBitmap() ?: filterInitialBitmap
                    if (
                        source != null &&
                        !source.isRecycled &&
                        currentFilter != null &&
                        currentFilter.name != getString(com.simplemobiletools.commons.R.string.none)
                    ) {
                        ensureBackgroundThread {
                            try {
                                val filtered = Bitmap.createBitmap(source)
                                currentFilter.filter.processFilter(filtered)
                                runOnUiThread {
                                    replaceWorkingBitmap(filtered)
                                    invalidateToolCaches()
                                    done()
                                }
                            } catch (e: OutOfMemoryError) {
                                runOnUiThread {
                                    toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
                                    done()
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    showErrorToast(e)
                                    done()
                                }
                            }
                        }
                        return
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            toast(com.simplemobiletools.commons.R.string.out_of_memory_error)
        } catch (e: Exception) {
            showErrorToast(e)
        }
        done()
    }
}
