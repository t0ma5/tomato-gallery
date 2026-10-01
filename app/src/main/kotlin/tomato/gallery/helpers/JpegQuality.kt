package tomato.gallery.helpers

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Maps a JPEG luminance quantization table back to an IJG quality setting.
 * Rotation uses this so a re-encode stays near the source file size.
 */
object JpegQuality {
    private val STD_LUMA = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99
    )

    fun estimate(header: ByteArray): Int? = estimate(ByteArrayInputStream(header))

    /**
     * Streaming variant: skips marker segments by length, so any size of
     * EXIF/XMP/thumbnail before the DQT does not matter.
     */
    fun estimate(input: InputStream): Int? {
        val table = readLumaQuantTable(input) ?: return null
        return qualityFromQuantTable(table)
    }

    fun qualityFromQuantTable(table: IntArray): Int {
        var scaleSum = 0.0
        var count = 0
        for (i in 0 until 64) {
            val std = STD_LUMA[i]
            scaleSum += table[i] * 100.0 / std
            count++
        }
        val scale = scaleSum / count
        val quality = if (scale <= 100.0) (200.0 - scale) / 2.0 else 5000.0 / scale
        return quality.toInt().coerceIn(1, 100)
    }

    fun quantTableForQuality(quality: Int): IntArray {
        val q = quality.coerceIn(1, 100)
        val scale = if (q < 50) 5000 / q else 200 - q * 2
        return IntArray(64) { i ->
            var temp = (STD_LUMA[i] * scale + 50) / 100
            if (temp <= 0) temp = 1
            if (temp > 255) temp = 255
            temp
        }
    }

    private fun readLumaQuantTable(input: InputStream): IntArray? {
        try {
            if (readByte(input) != 0xFF || readByte(input) != 0xD8) {
                return null
            }
            while (true) {
                var marker = readByte(input) ?: return null
                if (marker != 0xFF) {
                    continue
                }
                while (marker == 0xFF) {
                    marker = readByte(input) ?: return null
                }
                if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || marker in 0xD0..0xD7) {
                    continue
                }
                val hi = readByte(input) ?: return null
                val lo = readByte(input) ?: return null
                val len = (hi shl 8) or lo
                if (len < 2) {
                    return null
                }
                var remaining = len - 2
                if (marker == 0xDB) {
                    while (remaining > 0) {
                        val info = readByte(input) ?: return null
                        remaining--
                        val precision = info shr 4
                        val id = info and 0x0F
                        val values = if (precision == 0) 64 else 128
                        if (remaining < values) {
                            return null
                        }
                        if (id == 0 && precision == 0) {
                            val table = IntArray(64)
                            for (k in 0 until 64) {
                                table[k] = readByte(input) ?: return null
                            }
                            return table
                        }
                        skipFully(input, values.toLong())
                        remaining -= values
                    }
                    continue
                }
                if (marker == 0xDA) {
                    return null
                }
                skipFully(input, remaining.toLong())
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun readByte(input: InputStream): Int? {
        val b = input.read()
        return if (b < 0) null else b
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) {
                throw java.io.EOFException()
            }
            left--
        }
    }
}
