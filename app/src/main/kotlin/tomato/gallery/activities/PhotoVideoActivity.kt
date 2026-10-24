package tomato.gallery.activities

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.Html
import android.view.MotionEvent
import android.view.View
import android.widget.RelativeLayout
import com.simplemobiletools.commons.dialogs.PropertiesDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import tomato.gallery.BuildConfig
import tomato.gallery.R
import tomato.gallery.databinding.FragmentHolderBinding
import tomato.gallery.extensions.*
import tomato.gallery.fragments.PhotoFragment
import tomato.gallery.fragments.VideoFragment
import tomato.gallery.fragments.ViewPagerFragment
import tomato.gallery.helpers.*
import tomato.gallery.models.Medium
import java.io.File

open class PhotoVideoActivity : SimpleActivity(), ViewPagerFragment.FragmentListener, ViewerMotionHost {
    private var mMedium: Medium? = null
    private var mIsFullScreen = false
    private var mIsFromGallery = false
    private var mFragment: ViewPagerFragment? = null
    private var mUri: Uri? = null
    private var mOriginalBrightness: Float? = null

    var mIsVideo = false

    private var mExitBypass = false
    private lateinit var viewerMotion: ViewerMotion
    private lateinit var actionSheet: ActionSheetController

    private val binding by viewBinding(FragmentHolderBinding::inflate)

    public override fun onCreate(savedInstanceState: Bundle?) {
        showTransparentTop = true

        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.viewerScrim.setBackgroundColor(if (config.blackBackground) Color.BLACK else getProperBackgroundColor())
        binding.bottomActions.bottomMore.setImageResource(com.simplemobiletools.commons.R.drawable.ic_three_dots_vector)
        binding.bottomActions.bottomMore.setColorFilter(Color.WHITE)
        viewerMotion = ViewerMotion(
            this,
            binding.fragmentPlaceholder,
            binding.viewerScrim,
            { mMedium?.path ?: "" },
            { ViewerTransition.photoRect(it) },
        )
        actionSheet = ActionSheetController(binding.viewerMoreSheet, binding.viewerSheetDim, binding.bottomActions.bottomMore)
        actionSheet.wire()
        if (savedInstanceState == null) {
            ensureBackgroundThread { MotionPhotoHelper.clearStaleExtractions(applicationContext) }
        }
        setupEdgeToEdge(padBottomSystem = listOf(binding.bottomActions.bottomActionsWrapper))
        setupOptionsMenu()
        refreshMenuItems()
        handlePermission(getPermissionToRequest()) {
            if (it) {
                ensureAppUnlocked {
                    checkIntent(savedInstanceState)
                }
            } else {
                toast(com.simplemobiletools.commons.R.string.no_storage_permissions)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (config.bottomActions) {
            window.navigationBarColor = Color.TRANSPARENT
        } else {
            setTranslucentNavigation()
        }

        if (config.blackBackground) {
            updateStatusbarColor(Color.BLACK)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        window.applyMaxBrightness(false, mOriginalBrightness)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        initBottomActionsLayout()

        binding.topShadow.layoutParams.height = statusBarHeight + actionBarHeight
        (binding.fragmentViewerAppbar.layoutParams as RelativeLayout.LayoutParams).topMargin = statusBarHeight
        if (!portrait && navigationBarOnSide && navigationBarWidth > 0) {
            binding.fragmentViewerToolbar.setPadding(0, 0, navigationBarWidth, 0)
        } else {
            binding.fragmentViewerToolbar.setPadding(0, 0, 0, 0)
        }
    }

    fun refreshMenuItems() {
        val visibleBottomActions = if (config.bottomActions) config.visibleBottomActions else 0

        binding.fragmentViewerToolbar.menu.apply {
            findItem(R.id.menu_set_as).isVisible = mMedium?.isImage() == true && visibleBottomActions and BOTTOM_ACTION_SET_AS == 0
            findItem(R.id.menu_edit).isVisible = mMedium?.isImage() == true && mUri?.scheme == "file" && visibleBottomActions and BOTTOM_ACTION_EDIT == 0
            findItem(R.id.menu_properties).isVisible = mUri?.scheme == "file" && visibleBottomActions and BOTTOM_ACTION_PROPERTIES == 0
            findItem(R.id.menu_share).isVisible = visibleBottomActions and BOTTOM_ACTION_SHARE == 0
            findItem(R.id.menu_show_on_map).isVisible = visibleBottomActions and BOTTOM_ACTION_SHOW_ON_MAP == 0
        }
    }

    private fun setupOptionsMenu() {
        (binding.fragmentViewerAppbar.layoutParams as RelativeLayout.LayoutParams).topMargin = statusBarHeight
        binding.fragmentViewerToolbar.apply {
            setTitleTextColor(Color.WHITE)
            overflowIcon = resources.getColoredDrawableWithColor(com.simplemobiletools.commons.R.drawable.ic_three_dots_vector, Color.WHITE)
            navigationIcon = resources.getColoredDrawableWithColor(com.simplemobiletools.commons.R.drawable.ic_arrow_left_vector, Color.WHITE)
        }

        updateMenuItemColors(binding.fragmentViewerToolbar.menu, forceWhiteIcons = true)
        binding.fragmentViewerToolbar.setOnMenuItemClickListener { menuItem ->
            if (mMedium == null || mUri == null) {
                return@setOnMenuItemClickListener true
            }

            when (menuItem.itemId) {
                R.id.menu_set_as -> setAs(mUri!!.toString())
                R.id.menu_open_with -> openPath(mUri!!.toString(), true)
                R.id.menu_share -> sharePath(mUri!!.toString())
                R.id.menu_edit -> openEditor(mUri!!.toString())
                R.id.menu_properties -> showProperties()
                R.id.menu_show_on_map -> showFileOnMap(mUri!!.toString())
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }

        binding.fragmentViewerToolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun checkIntent(savedInstanceState: Bundle? = null) {
        if (intent.data == null && intent.action == Intent.ACTION_VIEW) {
            hideKeyboard()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        mUri = intent.data ?: return
        val uri = mUri.toString()
        val trustedUri = mUri!!.authority in setOf(
            "media",
            "com.android.providers.media.documents",
            "com.android.externalstorage.documents"
        )

        var filename = getFilenameFromUri(mUri!!)
        mIsFromGallery = intent.getBooleanExtra(IS_FROM_GALLERY, false)
        if (mIsFromGallery && filename.isVideoFast() && config.openVideosOnSeparateScreen) {
            launchVideoPlayer()
            return
        }

        if (intent.extras?.containsKey(REAL_FILE_PATH) == true) {
            val realPath = intent.extras!!.getString(REAL_FILE_PATH)
            if (realPath != null && getDoesFilePathExist(realPath)) {
                val isFileFolderHidden = (File(realPath).isHidden || File(realPath.getParentPath(), NOMEDIA).exists() || realPath.contains("/."))
                val preventShowingHiddenFile = (isRPlus() && !isExternalStorageManager()) && isFileFolderHidden
                if (!preventShowingHiddenFile) {
                    if (realPath.getFilenameFromPath().contains('.') || filename.contains('.')) {
                        if (isFileTypeVisible(realPath)) {
                            binding.bottomActions.root.beGone()
                            sendViewPagerIntent(realPath)
                            finish()
                            return
                        }
                    } else {
                        filename = realPath.getFilenameFromPath()
                    }
                }
            }
        }

        if (mUri!!.scheme == "file") {
            if (filename.contains('.')) {
                binding.bottomActions.root.beGone()
                rescanPaths(arrayListOf(mUri!!.path!!))
                sendViewPagerIntent(mUri!!.path!!)
                finish()
            }
            return
        } else {
            val realPath = applicationContext.getRealPathFromURI(mUri!!) ?: ""
            val isFileFolderHidden = (File(realPath).isHidden || File(realPath.getParentPath(), NOMEDIA).exists() || realPath.contains("/."))
            val preventShowingHiddenFile = (isRPlus() && !isExternalStorageManager()) && isFileFolderHidden
            if (trustedUri && !preventShowingHiddenFile) {
                if (realPath != mUri.toString() && realPath.isNotEmpty() && mUri!!.authority != "mms" && filename.contains('.') && getDoesFilePathExist(realPath)) {
                    if (isFileTypeVisible(realPath)) {
                        binding.bottomActions.root.beGone()
                        rescanPaths(arrayListOf(mUri!!.path!!))
                        sendViewPagerIntent(realPath)
                        finish()
                        return
                    }
                }
            }
        }

        binding.topShadow.layoutParams.height = statusBarHeight + actionBarHeight
        if (!portrait && navigationBarOnSide && navigationBarWidth > 0) {
            binding.fragmentViewerToolbar.setPadding(0, 0, navigationBarWidth, 0)
        } else {
            binding.fragmentViewerToolbar.setPadding(0, 0, 0, 0)
        }

        checkNotchSupport()
        showSystemUI(true)
        val bundle = Bundle()
        val file = File(mUri.toString())
        val intentType = intent.type ?: ""
        val type = when {
            filename.isVideoFast() || intentType.startsWith("video/") -> TYPE_VIDEOS
            filename.isGif() || intentType.equals("image/gif", true) -> TYPE_GIFS
            filename.isRawFast() -> TYPE_RAWS
            filename.isSvg() -> TYPE_SVGS
            file.isPortrait() -> TYPE_PORTRAITS
            else -> TYPE_IMAGES
        }

        mIsVideo = type == TYPE_VIDEOS
        mMedium = Medium(null, filename, mUri.toString(), mUri!!.path!!.getParentPath(), 0, 0, file.length(), type, 0, false, 0L, 0)
        binding.fragmentViewerToolbar.title = Html.fromHtml("<font color='${Color.WHITE.toHex()}'>${mMedium!!.name}</font>")
        bundle.putSerializable(MEDIUM, mMedium)

        if (savedInstanceState == null) {
            mFragment = if (mIsVideo) VideoFragment() else PhotoFragment()
            mFragment!!.listener = this
            mFragment!!.arguments = bundle
            supportFragmentManager.beginTransaction().replace(R.id.fragment_placeholder, mFragment!!).commit()
        } else {
            mFragment = supportFragmentManager.findFragmentById(R.id.fragment_placeholder) as? ViewPagerFragment
            mFragment?.listener = this
        }

        binding.fragmentPlaceholder.post {
            viewerMotion.playEnter(ViewerTransition.takePending(mMedium?.path ?: ""))
        }

        mOriginalBrightness = window.applyMaxBrightness(config.maxBrightness, mOriginalBrightness)

        initBottomActions()
    }

    private fun launchVideoPlayer() {
        val newUri = getFinalUriFromPath(mUri.toString(), BuildConfig.APPLICATION_ID)
        if (newUri == null) {
            toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
            return
        }

        hideKeyboard()
        val mimeType = getUriMimeType(mUri.toString(), newUri)
        Intent(applicationContext, VideoPlayerActivity::class.java).apply {
            setDataAndType(newUri, mimeType)
            addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
            if (intent.extras != null) {
                putExtras(intent.extras!!)
            }

            startActivity(this)
        }
        finish()
    }

    private fun sendViewPagerIntent(path: String) {
        ensureBackgroundThread {
            if (isPathPresentInMediaStore(path)) {
                openViewPager(path)
            } else {
                rescanPath(path) {
                    openViewPager(path)
                }
            }
        }
    }

    private fun openViewPager(path: String) {
        if (!intent.getBooleanExtra(IS_FROM_GALLERY, false)) {
            MediaActivity.mMedia.clear()
        }
        runOnUiThread {
            hideKeyboard()
            Intent(this, ViewPagerActivity::class.java).apply {
                putInternalNonce()
                putExtra(SKIP_AUTHENTICATION, mIsFromGallery && intent.getBooleanExtra(SKIP_AUTHENTICATION, false))
                putExtra(SHOW_FAVORITES, intent.getBooleanExtra(SHOW_FAVORITES, false))
                putExtra(IS_VIEW_INTENT, true)
                putExtra(IS_FROM_GALLERY, mIsFromGallery)
                putExtra(PATH, path)
                startActivity(this)
            }
        }
    }

    private fun isPathPresentInMediaStore(path: String): Boolean {
        val uri = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.Images.Media.DATA} = ?"
        val selectionArgs = arrayOf(path)

        try {
            val cursor = contentResolver.query(uri, null, selection, selectionArgs, null)
            cursor?.use {
                return cursor.moveToFirst()
            }
        } catch (e: Exception) {
        }

        return false
    }

    private fun showProperties() {
        PropertiesDialog(this, mUri!!.path!!)
    }

    private fun isFileTypeVisible(path: String): Boolean {
        val filter = config.filterMedia
        return !(path.isGalleryImageFast() && filter and TYPE_IMAGES == 0 ||
            path.isVideoFast() && filter and TYPE_VIDEOS == 0 ||
            path.isGif() && filter and TYPE_GIFS == 0 ||
            path.isRawFast() && filter and TYPE_RAWS == 0 ||
            path.isSvg() && filter and TYPE_SVGS == 0 ||
            path.isPortrait() && filter and TYPE_PORTRAITS == 0)
    }

    private fun initBottomActions() {
        initBottomActionButtons()
        initBottomActionsLayout()
    }

    private fun initBottomActionsLayout() {
        binding.bottomActions.root.layoutParams.height = resources.getDimension(R.dimen.bottom_actions_height).toInt() + navigationBarHeight
        if (config.bottomActions) {
            binding.bottomActions.root.beVisible()
        } else {
            binding.bottomActions.root.beGone()
        }
    }

    private fun initBottomActionButtons() {
        arrayListOf(
            binding.bottomActions.bottomFavorite,
            binding.bottomActions.bottomDelete,
            binding.bottomActions.bottomRotate,
            binding.bottomActions.bottomProperties,
            binding.bottomActions.bottomChangeOrientation,
            binding.bottomActions.bottomSlideshow,
            binding.bottomActions.bottomShowOnMap,
            binding.bottomActions.bottomToggleFileVisibility,
            binding.bottomActions.bottomRename,
            binding.bottomActions.bottomCopy,
            binding.bottomActions.bottomMove,
            binding.bottomActions.bottomResize,
        ).forEach {
            it.beGone()
        }

        val visibleBottomActions = if (config.bottomActions) config.visibleBottomActions else 0
        binding.bottomActions.bottomEdit.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_EDIT != 0 && mMedium?.isImage() == true)
        binding.bottomActions.bottomEdit.setOnClickListener {
            if (mUri != null && binding.bottomActions.root.translationY == 0f) {
                openEditor(mUri!!.toString())
            }
        }

        binding.bottomActions.bottomShare.beVisibleIf(visibleBottomActions and BOTTOM_ACTION_SHARE != 0)
        binding.bottomActions.bottomShare.setOnClickListener {
            if (mUri != null && binding.bottomActions.root.translationY == 0f) {
                sharePath(mUri!!.toString())
            }
        }

        binding.bottomActions.bottomSetAs.setOnClickListener {
            setAs(mUri!!.toString())
        }

        binding.bottomActions.bottomShowOnMap.setOnClickListener {
            showFileOnMap(mUri!!.toString())
        }
        // computed from config, never read back from the views (they stay gone and live in the More sheet)
        val secondary = listOf<Pair<View, Boolean>>(
            binding.bottomActions.bottomSetAs to (visibleBottomActions and BOTTOM_ACTION_SET_AS != 0 && mMedium?.isImage() == true),
            binding.bottomActions.bottomShowOnMap to (visibleBottomActions and BOTTOM_ACTION_SHOW_ON_MAP != 0),
        )
        secondary.forEach { it.first.beGone() }
        actionSheet.bind(secondary.filter { it.second }.map { it.first })
    }

    override fun fragmentClicked() {
        mIsFullScreen = !mIsFullScreen
        if (mIsFullScreen) {
            hideSystemUI(true)
        } else {
            showSystemUI(true)
        }

        actionSheet.closeIfOpen()
        val topTravel = statusBarHeight + binding.fragmentViewerAppbar.height.toFloat()
        ChromeMotion.slide(binding.topShadow, mIsFullScreen, upward = true, distance = topTravel)
        ChromeMotion.slide(binding.fragmentViewerAppbar, mIsFullScreen, upward = true, distance = topTravel)
        if (binding.bottomActions.root.isVisible()) {
            ChromeMotion.slide(binding.bottomActions.root, mIsFullScreen, upward = false)
        }
    }

    override fun finish() {
        if (!mExitBypass && ::viewerMotion.isInitialized && viewerMotion.interceptFinish {
                mExitBypass = true
                finish()
            }
        ) {
            return
        }
        if (::viewerMotion.isInitialized && viewerMotion.isExiting) {
            mExitBypass = true
        }
        super.finish()
        if (mExitBypass) {
            overridePendingTransition(0, 0)
        }
    }

    override fun onViewerTouch(event: MotionEvent): Boolean {
        return if (::viewerMotion.isInitialized) viewerMotion.onTouch(event) else false
    }

    override fun videoEnded() = false

    override fun goToPrevItem() {}

    override fun goToNextItem() {}

    override fun launchViewVideoIntent(path: String) {}

    override fun isSlideShowActive() = false

    override fun interruptSlideshowDrag() {}
}
