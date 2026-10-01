package tomato.gallery.dialogs

import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import tomato.gallery.R
import tomato.gallery.databinding.DialogOptimizeJpegsBinding
import tomato.gallery.extensions.config
import tomato.gallery.extensions.ensureWriteAccess
import tomato.gallery.extensions.rescanPathsAndUpdateLastModified
import tomato.gallery.helpers.JpegOptim
import java.io.File

class OptimizeJpegsDialog(
    private val activity: BaseSimpleActivity,
    private val imagePaths: List<String>,
    private val callback: () -> Unit
) {
    @Volatile
    private var cancelled = false
    private var dialog: AlertDialog? = null
    private val binding = DialogOptimizeJpegsBinding.inflate(activity.layoutInflater)
    private val progressView = binding.optimizeProgress

    init {
        val config = activity.config
        progressView.apply {
            max = imagePaths.size
            setIndicatorColor(activity.getProperPrimaryColor())
        }

        binding.optimizeQuality.setColors(activity.getProperTextColor(), activity.getProperPrimaryColor(), activity.getProperBackgroundColor())
        binding.optimizeLossless.isChecked = config.jpegOptimLossless
        binding.optimizeQuality.progress = config.jpegOptimQuality.coerceIn(50, 100)
        binding.optimizeLossless.setOnCheckedChangeListener { _, _ -> refreshQualityUi() }
        binding.optimizeQuality.onSeekBarChangeListener {
            refreshQualityUi()
        }
        refreshQualityUi()

        activity.getAlertDialogBuilder()
            .setPositiveButton(com.simplemobiletools.commons.R.string.ok, null)
            .setNegativeButton(com.simplemobiletools.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.optimize_jpegs) { alertDialog ->
                    dialog = alertDialog
                    val positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    val negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    positiveButton.setOnClickListener {
                        alertDialog.setCancelable(false)
                        alertDialog.setCanceledOnTouchOutside(false)
                        arrayOf(
                            binding.optimizeMessage,
                            binding.optimizeLossless,
                            binding.optimizeQualityLabel,
                            binding.optimizeQuality,
                            positiveButton
                        ).forEach {
                            it.isEnabled = false
                            it.alpha = 0.6f
                        }
                        negativeButton.setOnClickListener { cancelled = true }
                        requestWriteAccessThenOptimize()
                    }
                }
            }
    }

    private fun refreshQualityUi() {
        val lossless = binding.optimizeLossless.isChecked
        binding.optimizeQuality.isEnabled = !lossless
        binding.optimizeQuality.alpha = if (lossless) 0.4f else 1f
        binding.optimizeQualityLabel.text = if (lossless) {
            activity.getString(R.string.optimize_jpegs_lossless_hint)
        } else {
            activity.getString(R.string.optimize_jpegs_quality, binding.optimizeQuality.progress)
        }
    }

    private fun requestWriteAccessThenOptimize() {
        val parents = imagePaths.map { it.getParentPath() }.distinct()
        fun next(index: Int) {
            if (index >= parents.size) {
                optimizeImages()
            } else {
                activity.ensureWriteAccess(parents[index]) {
                    next(index + 1)
                }
            }
        }
        next(0)
    }

    private fun optimizeImages() {
        val lossless = binding.optimizeLossless.isChecked
        val qualitySetting = binding.optimizeQuality.progress.coerceIn(50, 100)
        activity.config.jpegOptimLossless = lossless
        activity.config.jpegOptimQuality = qualitySetting
        val nativeQuality = if (lossless) -1 else qualitySetting

        progressView.show()
        val pathsToRescan = arrayListOf<String>()
        val pathLastModifiedMap = mutableMapOf<String, Long>()
        var savedTotal = 0L
        var optimizedCount = 0
        var failureCount = 0
        var skippedCount = 0

        ensureBackgroundThread {
            for (i in imagePaths.indices) {
                if (cancelled) {
                    break
                }
                val path = imagePaths[i]
                val lastModified = File(path).lastModified()
                val result = JpegOptim.optimize(activity, path, nativeQuality)
                when {
                    result.skipped -> skippedCount++
                    !result.ok -> failureCount++
                    result.bytesSaved > 0L -> {
                        optimizedCount++
                        savedTotal += result.bytesSaved
                        pathsToRescan.add(path)
                        pathLastModifiedMap[path] = lastModified
                    }
                }
                activity.runOnUiThread {
                    progressView.progress = i + 1
                }
            }

            activity.runOnUiThread {
                val parts = ArrayList<String>()
                when {
                    cancelled -> Unit
                    failureCount > 0 && optimizedCount == 0 && skippedCount == 0 -> {
                        parts.add(activity.getString(R.string.jpeg_optimize_failed))
                    }
                    optimizedCount == 0 && skippedCount == 0 -> {
                        parts.add(activity.getString(R.string.jpegs_already_optimized))
                    }
                    optimizedCount > 0 -> {
                        parts.add(activity.getString(R.string.jpegs_optimized, optimizedCount, savedTotal.formatSize()))
                    }
                }
                if (skippedCount > 0) {
                    parts.add(activity.getString(R.string.jpegs_skipped_hdr, skippedCount))
                }
                if (failureCount > 0 && optimizedCount > 0) {
                    parts.add(activity.getString(R.string.jpegs_optimize_failures, failureCount))
                }
                if (parts.isNotEmpty()) {
                    activity.toast(parts.joinToString(" "))
                }
            }

            if (pathsToRescan.isEmpty()) {
                activity.runOnUiThread {
                    dialog?.dismiss()
                    callback.invoke()
                }
            } else {
                activity.rescanPathsAndUpdateLastModified(pathsToRescan, pathLastModifiedMap) {
                    activity.runOnUiThread {
                        dialog?.dismiss()
                        callback.invoke()
                    }
                }
            }
        }
    }
}
