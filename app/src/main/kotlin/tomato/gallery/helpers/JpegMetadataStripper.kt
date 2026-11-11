package tomato.gallery.helpers

import java.io.ByteArrayOutputStream

/**
 * Rewrites a JPEG keeping only what is needed to show the image: JFIF (without its thumbnail),
 * the ICC profile (colors of Display P3 photos), Adobe APP14 (CMYK/YCCK decoding), tables and scans.
 * Drops EXIF with its IFD1 thumbnail, XMP, IPTC, comments, maker segments and anything after EOI
 * (motion photo video, Ultra HDR gain map, vendor trailers). ExifInterface can't do this, it re-emits
 * the thumbnail and copies APP2/APP13 through. Returns null for anything it can't parse.
 */
object JpegMetadataStripper {
    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP0 = 0xE0
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE

    private val JFIF = "JFIF\u0000".toByteArray(Charsets.ISO_8859_1)
    private val ICC = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
    private val ADOBE = "Adobe".toByteArray(Charsets.ISO_8859_1)

    // JFIF APP0 up to and including Xthumbnail/Ythumbnail
    private const val JFIF_HEADER_SIZE = 14

    fun isJpeg(bytes: ByteArray) = bytes.size >= 2 && bytes.u8(0) == 0xFF && bytes.u8(1) == SOI

    fun strip(src: ByteArray): ByteArray? {
        if (!isJpeg(src)) {
            return null
        }

        val out = ByteArrayOutputStream(src.size)
        out.write(0xFF)
        out.write(SOI)
        var i = 2
        while (i < src.size) {
            if (src.u8(i) != 0xFF) {
                return null
            }
            // fill bytes before a marker
            while (i < src.size && src.u8(i) == 0xFF) {
                i++
            }
            if (i >= src.size) {
                return null
            }
            val marker = src.u8(i++)
            when {
                marker == EOI -> {
                    out.write(0xFF)
                    out.write(EOI)
                    return out.toByteArray()
                }
                // standalone markers, no length
                marker == 0x01 || marker in 0xD0..0xD7 -> {
                    out.write(0xFF)
                    out.write(marker)
                }
                else -> {
                    if (i + 2 > src.size) {
                        return null
                    }
                    val length = (src.u8(i) shl 8) or src.u8(i + 1)
                    if (length < 2 || i + length > src.size) {
                        return null
                    }
                    val payloadStart = i + 2
                    val payloadEnd = i + length
                    when {
                        marker == APP0 && src.startsWith(JFIF, payloadStart, payloadEnd) -> {
                            if (length - 2 < JFIF_HEADER_SIZE) {
                                return null
                            }
                            // same header, thumbnail size set to 0x0 and its pixels dropped
                            val header = src.copyOfRange(payloadStart, payloadStart + JFIF_HEADER_SIZE)
                            header[JFIF_HEADER_SIZE - 2] = 0
                            header[JFIF_HEADER_SIZE - 1] = 0
                            writeSegment(out, marker, header, 0, JFIF_HEADER_SIZE)
                        }
                        marker == APP2 && src.startsWith(ICC, payloadStart, payloadEnd) -> writeSegment(out, marker, src, payloadStart, length - 2)
                        marker == APP14 && src.startsWith(ADOBE, payloadStart, payloadEnd) -> writeSegment(out, marker, src, payloadStart, length - 2)
                        // every other APPn (EXIF, XMP, MPF, IPTC, maker data) and COM
                        marker in 0xE0..0xEF || marker == 0xFE -> Unit
                        else -> writeSegment(out, marker, src, payloadStart, length - 2)
                    }
                    i = payloadEnd

                    if (marker == SOS) {
                        // entropy-coded data runs to the next marker that is not a stuffed 0x00 or a restart
                        val dataStart = i
                        while (i < src.size) {
                            if (src.u8(i) == 0xFF && i + 1 < src.size) {
                                val next = src.u8(i + 1)
                                if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) {
                                    break
                                }
                            }
                            i++
                        }
                        out.write(src, dataStart, i - dataStart)
                    }
                }
            }
        }
        // no EOI
        return null
    }

    private fun writeSegment(out: ByteArrayOutputStream, marker: Int, src: ByteArray, payloadStart: Int, payloadSize: Int) {
        val length = payloadSize + 2
        out.write(0xFF)
        out.write(marker)
        out.write(length shr 8)
        out.write(length and 0xFF)
        out.write(src, payloadStart, payloadSize)
    }

    private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF

    private fun ByteArray.startsWith(prefix: ByteArray, from: Int, end: Int): Boolean {
        if (end - from < prefix.size) {
            return false
        }
        return prefix.indices.all { this[from + it] == prefix[it] }
    }
}
