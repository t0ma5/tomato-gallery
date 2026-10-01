package tomato.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegQualityTest {
    @Test
    fun roundTripCommonQualities() {
        listOf(50, 60, 75, 85, 90).forEach { quality ->
            val header = dqtSegment(JpegQuality.quantTableForQuality(quality))
            val estimated = JpegQuality.estimate(header)
            assertTrue("$quality -> $estimated", estimated != null && kotlin.math.abs(estimated - quality) <= 2)
        }
    }

    @Test
    fun nonJpegReturnsNull() {
        assertNull(JpegQuality.estimate(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun largeExifBeforeDqtIsSkipped() {
        // Two 60 KB APP1 segments put the DQT past the first 120 KB.
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0xD8)
        repeat(2) {
            out.write(0xFF)
            out.write(0xE1)
            val payload = 60_000
            val len = payload + 2
            out.write(len shr 8)
            out.write(len and 0xFF)
            out.write(ByteArray(payload))
        }
        val table = JpegQuality.quantTableForQuality(75)
        out.write(0xFF)
        out.write(0xDB)
        val dqtLen = 2 + 1 + 64
        out.write(dqtLen shr 8)
        out.write(dqtLen and 0xFF)
        out.write(0)
        table.forEach { out.write(it) }

        val estimated = JpegQuality.estimate(out.toByteArray())
        assertTrue("expected ~75, got $estimated", estimated != null && kotlin.math.abs(estimated - 75) <= 2)
    }

    @Test
    fun quality50TableIsTheStandardLumaTable() {
        val table = JpegQuality.quantTableForQuality(50)
        assertEquals(16, table[0])
        assertEquals(99, table[63])
        assertEquals(50, JpegQuality.qualityFromQuantTable(table))
    }

    private fun dqtSegment(table: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF)
        out.write(0xD8)
        out.write(0xFF)
        out.write(0xDB)
        val len = 2 + 1 + 64
        out.write(len shr 8)
        out.write(len and 0xFF)
        out.write(0)
        table.forEach { out.write(it) }
        return out.toByteArray()
    }
}
