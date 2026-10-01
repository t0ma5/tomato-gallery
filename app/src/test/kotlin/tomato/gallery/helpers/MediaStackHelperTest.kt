package tomato.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tomato.gallery.models.Medium

class MediaStackHelperTest {
    private fun medium(name: String, type: Int, parent: String = "/sd/DCIM"): Medium {
        return Medium(
            null, name, "$parent/$name", parent, 0L, 0L, 100L, type, 0, false, 0L, 0L
        )
    }

    @Test
    fun rawJpegPairStacks() {
        val media = arrayListOf(
            medium("IMG_0001.JPG", TYPE_IMAGES),
            medium("IMG_0001.DNG", TYPE_RAWS)
        )
        val stacked = MediaStackHelper.stack(media)
        assertEquals(1, stacked.size)
        assertEquals(2, stacked.first().stackMembers.size)
    }

    @Test
    fun similarNamesDoNotStack() {
        val media = arrayListOf(
            medium("Starburst.jpg", TYPE_IMAGES),
            medium("Star.jpg", TYPE_IMAGES)
        )
        val stacked = MediaStackHelper.stack(media)
        assertEquals(2, stacked.size)
        assertTrue(stacked.all { it.stackMembers.isEmpty() })
    }
}
