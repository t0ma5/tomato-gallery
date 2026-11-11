package tomato.gallery.helpers

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegMetadataStripperTest {
    private val scanData = byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56)

    @Test
    fun dropsMetadataKeepsImageAndColor() {
        val src = jpeg {
            // JFIF with a 2x1 RGB thumbnail
            segment(0xE0, "JFIF\u0000".latin1() + byteArrayOf(1, 2, 0, 0, 1, 0, 1, 2, 1) + ByteArray(6) { 0x7F })
            segment(0xE1, "Exif\u0000\u0000".latin1() + "SECRET-EXIF".latin1())
            segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".latin1() + "SECRET-XMP".latin1())
            segment(0xE2, "ICC_PROFILE\u0000".latin1() + "P3".latin1())
            segment(0xE2, "MPF\u0000".latin1() + "SECRET-MPF".latin1())
            segment(0xED, "Photoshop 3.0\u0000".latin1() + "SECRET-IPTC".latin1())
            segment(0xFE, "SECRET-COMMENT".latin1())
            segment(0xDB, ByteArray(65))
            segment(0xC0, ByteArray(15))
            segment(0xC4, ByteArray(20))
            segment(0xDA, ByteArray(10))
            write(scanData)
            marker(0xD9)
            write("SECRET-TRAILER-VIDEO".latin1())
        }

        val out = JpegMetadataStripper.strip(src)!!
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse(text.contains("SECRET"))
        assertTrue(text.contains("ICC_PROFILE"))
        assertTrue(text.contains("JFIF"))
        assertTrue(String(out, Charsets.ISO_8859_1).contains(String(scanData, Charsets.ISO_8859_1)))
        assertEquals(0xFF, out[out.size - 2].toInt() and 0xFF)
        assertEquals(0xD9, out[out.size - 1].toInt() and 0xFF)

        // JFIF: length 16, thumbnail 0x0, no thumbnail pixels
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16), out.copyOfRange(2, 6))
        assertEquals(0, out[6 + 12].toInt())
        assertEquals(0, out[6 + 13].toInt())
    }

    @Test
    fun progressiveScansAreKept() {
        val src = jpeg {
            segment(0xC2, ByteArray(15))
            segment(0xDA, ByteArray(10))
            write(byteArrayOf(1, 2, 3))
            segment(0xC4, ByteArray(20))
            segment(0xDA, ByteArray(10))
            write(byteArrayOf(4, 5, 6))
            marker(0xD9)
        }
        assertArrayEquals(src, JpegMetadataStripper.strip(src))
    }

    @Test
    fun malformedReturnsNull() {
        assertNull(JpegMetadataStripper.strip(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        // segment length runs past the end
        assertNull(JpegMetadataStripper.strip(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x10, 0x00)))
        // no EOI
        assertNull(JpegMetadataStripper.strip(jpeg { segment(0xDB, ByteArray(65)) }))
    }

    private fun String.latin1() = toByteArray(Charsets.ISO_8859_1)

    private fun jpeg(body: ByteArrayOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        out.marker(0xD8)
        out.body()
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.marker(marker: Int) {
        write(0xFF)
        write(marker)
    }

    private fun ByteArrayOutputStream.segment(marker: Int, payload: ByteArray) {
        marker(marker)
        val length = payload.size + 2
        write(length shr 8)
        write(length and 0xFF)
        write(payload)
    }
}
