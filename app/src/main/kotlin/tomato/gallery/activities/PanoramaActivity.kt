package tomato.gallery.activities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import com.simplemobiletools.commons.extensions.getFilenameFromPath
import com.simplemobiletools.commons.extensions.toast
import com.simplemobiletools.commons.extensions.viewBinding
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import tomato.gallery.R
import tomato.gallery.databinding.ActivityPanoramaBinding
import com.simplemobiletools.commons.extensions.isVideoFast
import tomato.gallery.helpers.PATH
import tomato.gallery.panorama.PanoramaGLSurfaceView
import tomato.gallery.extensions.setupEdgeToEdge
import java.io.File
import kotlin.math.abs

class PanoramaActivity : SimpleActivity(), SensorEventListener {
    private val binding by viewBinding(ActivityPanoramaBinding::inflate)
    private var sensorManager: SensorManager? = null
    private var gyroEnabled = false
    private var lastYaw = 0f
    private var lastPitch = 0f
    private var hasGyroSample = false

    override fun onCreate(savedInstanceState: Bundle?) {
        showTransparentTop = true
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.panoramaToolbarHolder),
            padBottomSystem = listOf(binding.panoramaGyroHolder)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val path = intent.getStringExtra(PATH) ?: ""
        if (path.isEmpty()) {
            toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
            finish()
            return
        }

        binding.panoramaToolbar.title = path.getFilenameFromPath()
        binding.panoramaToolbar.setNavigationOnClickListener { finish() }
        binding.panoramaGyro.setOnClickListener { toggleGyro() }
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        ensureAppUnlocked {
            loadPanorama(path)
        }
    }

    private fun loadPanorama(path: String) {
        ensureBackgroundThread {
            val bitmap = loadBitmap(path)
            runOnUiThread {
                if (bitmap == null) {
                    toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                    finish()
                    return@runOnUiThread
                }
                val projection = if (bitmap.width.toFloat() / bitmap.height >= 1.9f) {
                    PanoramaGLSurfaceView.Projection.SPHERE
                } else {
                    PanoramaGLSurfaceView.Projection.CYLINDER
                }
                binding.panoramaView.setBitmap(bitmap, projection)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.panoramaView.onResume()
        if (gyroEnabled) {
            registerGyro()
        }
    }

    override fun onPause() {
        binding.panoramaView.onPause()
        super.onPause()
        unregisterGyro()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!gyroEnabled) {
            return
        }

        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        val yaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
        if (hasGyroSample) {
            var deltaYaw = yaw - lastYaw
            if (deltaYaw > 180f) deltaYaw -= 360f
            if (deltaYaw < -180f) deltaYaw += 360f
            val deltaPitch = pitch - lastPitch
            if (abs(deltaYaw) < 40f && abs(deltaPitch) < 40f) {
                binding.panoramaView.addRotation(deltaYaw, deltaPitch)
            }
        }
        lastYaw = yaw
        lastPitch = pitch
        hasGyroSample = true
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun toggleGyro() {
        gyroEnabled = !gyroEnabled
        hasGyroSample = false
        binding.panoramaGyro.alpha = if (gyroEnabled) 1f else 0.45f
        if (gyroEnabled) registerGyro() else unregisterGyro()
    }

    private fun registerGyro() {
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null) {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            gyroEnabled = false
            toast(R.string.gyro_unavailable)
        }
    }

    private fun unregisterGyro() {
        sensorManager?.unregisterListener(this)
    }

    private fun loadBitmap(path: String): Bitmap? {
        return try {
            if (path.isVideoFast()) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (path.startsWith("content://")) {
                        retriever.setDataSource(this, Uri.parse(path))
                    } else {
                        retriever.setDataSource(path)
                    }
                    retriever.getFrameAtTime(0)
                } finally {
                    retriever.release()
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                decode(path, opts)
                opts.inJustDecodeBounds = false
                opts.inSampleSize = sampleSize(opts.outWidth, opts.outHeight, 4096)
                decode(path, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decode(path: String, opts: BitmapFactory.Options): Bitmap? {
        return if (path.startsWith("content://")) {
            contentResolver.openInputStream(Uri.parse(path))?.use { BitmapFactory.decodeStream(it, null, opts) }
        } else {
            BitmapFactory.decodeFile(File(path).absolutePath, opts)
        }
    }

    private fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w > maxSide || h > maxSide) {
            sample *= 2
            w /= 2
            h /= 2
        }
        return sample.coerceAtLeast(1)
    }
}
