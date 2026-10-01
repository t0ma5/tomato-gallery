package tomato.gallery.activities

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.TextureView
import android.widget.SeekBar
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.simplemobiletools.commons.extensions.getFormattedDuration
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.commons.helpers.NavigationIcon
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import tomato.gallery.R
import tomato.gallery.databinding.ActivityTrimVideoBinding
import com.simplemobiletools.commons.extensions.viewBinding
import tomato.gallery.helpers.PATH
import tomato.gallery.helpers.VideoTrimHelper
import tomato.gallery.extensions.setupEdgeToEdge
import java.io.File

@UnstableApi
class TrimVideoActivity : SimpleActivity(), TextureView.SurfaceTextureListener {
    private val binding by viewBinding(ActivityTrimVideoBinding::inflate)
    private var player: ExoPlayer? = null
    private var path = ""
    private var durationMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        isMaterialActivity = true
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.trimAppBar),
            padBottomSystem = listOf(binding.trimHolder)
        )
        binding.trimToolbar.inflateMenu(R.menu.menu_trim_video)
        binding.trimToolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.save_trim) {
                saveTrim()
                true
            } else {
                false
            }
        }
        path = intent.getStringExtra(PATH) ?: ""
        if (path.isEmpty() || !File(path).exists()) {
            toast(R.string.invalid_image_path)
            finish()
            return
        }
        binding.trimSurface.surfaceTextureListener = this
        binding.trimStart.setOnSeekBarChangeListener(seekListener { previewStart() })
        binding.trimEnd.setOnSeekBarChangeListener(seekListener { previewEnd() })
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(binding.trimToolbar, NavigationIcon.Arrow)
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        exo.setVideoSurface(android.view.Surface(surface))
        exo.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path))))
        exo.prepare()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && durationMs == 0L) {
                    durationMs = exo.duration.coerceAtLeast(1000L)
                    val seconds = (durationMs / 1000).toInt().coerceAtLeast(1)
                    binding.trimStart.max = seconds
                    binding.trimEnd.max = seconds
                    binding.trimStart.progress = 0
                    binding.trimEnd.progress = seconds
                    updateLabels()
                }
            }
        })
        exo.playWhenReady = false
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        player?.clearVideoSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    private fun seekListener(onStop: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                if (binding.trimStart.progress >= binding.trimEnd.progress) {
                    if (seekBar === binding.trimStart) {
                        binding.trimStart.progress = (binding.trimEnd.progress - 1).coerceAtLeast(0)
                    } else {
                        binding.trimEnd.progress = (binding.trimStart.progress + 1).coerceAtMost(binding.trimEnd.max)
                    }
                }
                updateLabels()
            }
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: SeekBar?) = onStop()
    }

    private fun previewStart() {
        player?.seekTo(binding.trimStart.progress * 1000L)
        player?.playWhenReady = false
    }

    private fun previewEnd() {
        player?.seekTo(binding.trimEnd.progress * 1000L)
        player?.playWhenReady = false
    }

    private fun updateLabels() {
        binding.trimStartLabel.text = getString(R.string.trim_start) + ": " + binding.trimStart.progress.getFormattedDuration()
        binding.trimEndLabel.text = getString(R.string.trim_end) + ": " + binding.trimEnd.progress.getFormattedDuration()
    }

    private fun saveTrim() {
        val startMs = binding.trimStart.progress * 1000L
        val endMs = binding.trimEnd.progress * 1000L
        if (endMs - startMs < 200L) {
            toast(R.string.trim_failed)
            return
        }
        toast(com.simplemobiletools.commons.R.string.saving)
        val dest = VideoTrimHelper.outputPath(path)
        ensureBackgroundThread {
            VideoTrimHelper.trim(this, path, startMs, endMs, dest) { ok ->
                toast(if (ok) R.string.video_trimmed else R.string.trim_failed)
                if (ok) {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }
}
