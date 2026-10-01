package tomato.gallery.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataStripperTest {
    @Test
    fun stripGpsRemovesXmpAttributesAndElements() {
        val xmp = """
            <x:xmpmeta>
              <rdf:Description exif:GPSLatitude="1,2,3" exif:GPSLongitude="4,5,6">
                <exif:GPSAltitude>10</exif:GPSAltitude>
                <dc:title>keep me</dc:title>
              </rdf:Description>
            </x:xmpmeta>
        """.trimIndent()
        val stripped = MetadataStripper.stripGpsFromXmp(xmp)
        assertFalse(stripped.contains("GPSLatitude"))
        assertFalse(stripped.contains("GPSAltitude"))
        assertTrue(stripped.contains("keep me"))
    }
}
