package tomato.gallery.helpers

import androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
import androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
import androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationTransformationTest {
    @Test
    fun normalDoesNotSwap() {
        assertFalse(OrientationTransformation.whSwapped(ORIENTATION_NORMAL))
        assertFalse(OrientationTransformation.whSwapped(ORIENTATION_FLIP_HORIZONTAL))
    }

    @Test
    fun rotate90Swaps() {
        assertTrue(OrientationTransformation.whSwapped(ORIENTATION_ROTATE_90))
        assertTrue(OrientationTransformation.whSwapped(ORIENTATION_TRANSPOSE))
    }
}
