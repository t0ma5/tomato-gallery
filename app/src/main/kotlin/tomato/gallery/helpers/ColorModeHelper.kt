package tomato.gallery.helpers

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Color modes for HDR and wide-gamut images on Android 14+.
 */
object ColorModeHelper {

    fun isGainmapSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun hasHdrContent(bitmap: Bitmap?): Boolean {
        return bitmap?.hasGainmap() == true
    }

    fun hasWideColorGamut(bitmap: Bitmap?): Boolean {
        return bitmap?.colorSpace?.isWideGamut == true
    }

    fun setColorMode(activity: Activity, colorMode: Int) {
        activity.window.setColorMode(colorMode)
    }

    fun setColorModeForImage(activity: Activity, bitmap: Bitmap?, ultraHdr: Boolean = true) {
        val hdr = ultraHdr && isGainmapSupported() && bitmap != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()
        setColorMode(
            activity = activity,
            colorMode = when {
                hdr -> ActivityInfo.COLOR_MODE_HDR
                hasWideColorGamut(bitmap) -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
                else -> ActivityInfo.COLOR_MODE_DEFAULT
            }
        )
    }

    fun resetColorMode(activity: Activity?) {
        activity?.window?.setColorMode(ActivityInfo.COLOR_MODE_DEFAULT)
    }
}
