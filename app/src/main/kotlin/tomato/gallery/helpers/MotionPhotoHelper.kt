package tomato.gallery.helpers

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.UUID

/**
 * Describes the embedded video inside a Motion Photo / Micro Video file.
 *
 * [videoOffset] is the byte offset from the **end** of the file where the MP4 starts.
 * Adapted from ReFra (Apache-2.0), IacobIonut01/ReFra.
 */
data class MotionPhotoInfo(
    val videoOffset: Long,
    val presentationTimestampUs: Long = -1L
)

object MotionPhotoHelper {
    private const val KEY_MOTION_PHOTO = "GCamera:MotionPhoto"
    private const val KEY_MOTION_PHOTO_OFFSET = "GCamera:MotionPhotoVideoOffset"
    private const val KEY_MOTION_PHOTO_PTS = "GCamera:MotionPhotoPresentationTimestampUs"
    private const val KEY_MICRO_VIDEO = "GCamera:MicroVideo"
    private const val KEY_MICRO_VIDEO_OFFSET = "GCamera:MicroVideoOffset"
    private const val KEY_MICRO_VIDEO_PTS = "GCamera:MicroVideoPresentationTimestampUs"
    private const val SEMANTIC_MOTION_PHOTO = "MotionPhoto"
    private const val ITEM_SEMANTIC_SUFFIX = "/Item:Semantic"
    private const val ITEM_LENGTH_SUFFIX = "/Item:Length"
    private const val ITEM_PADDING_SUFFIX = "/Item:Padding"
    private val SAMSUNG_MARKER = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
    private const val SAMSUNG_TAIL_SCAN = 64 * 1024L
    private val ATTR = Regex("""([A-Za-z0-9]+:[A-Za-z0-9]+)="([^"]+)"""")
    private val ELEM = Regex("""<([A-Za-z0-9]+:[A-Za-z0-9]+)>([^<]+)</""")
    private val LATIN_1: Charset = Charset.forName("ISO-8859-1")

    private const val PROBE_BYTES = 256 * 1024
    private const val EXTRACTION_MAX_AGE_MS = 5 * 60 * 1000L

    fun parseInfo(context: Context, uri: Uri): MotionPhotoInfo? {
        val probe = readProbe(context, uri) ?: return null
        return parseInfo(context, uri, probe)
    }

    /** Uses an already-read file head (see [readProbe]) so callers can share one open for all XMP checks. */
    fun parseInfo(context: Context, uri: Uri, probe: ByteArray, text: String = probeText(probe)): MotionPhotoInfo? {
        return try {
            if (!looksLikeJpegOrHeic(probe)) {
                return null
            }

            val xmpResult = resolveInfo(parseXmpProperties(text), parseContainerItems(text))
            if (xmpResult != null) {
                return xmpResult
            }

            val offset = findSamsungMarkerOffset(context, uri) ?: return null
            MotionPhotoInfo(offset)
        } catch (_: Throwable) {
            null
        }
    }

    fun parseInfo(context: Context, path: String): MotionPhotoInfo? = parseInfo(context, pathToUri(path))

    fun readProbe(context: Context, uri: Uri, maxBytes: Int = PROBE_BYTES): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(maxBytes)
                var total = 0
                while (total < maxBytes) {
                    val n = stream.read(buf, total, maxBytes - total)
                    if (n <= 0) break
                    total += n
                }
                if (total == maxBytes) buf else buf.copyOf(total)
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun probeText(probe: ByteArray): String = String(probe, LATIN_1)

    /** Removes extracted clips left behind by a killed process. Recent files may still be playing, so they stay. */
    fun clearStaleExtractions(context: Context) {
        try {
            val cutoff = System.currentTimeMillis() - EXTRACTION_MAX_AGE_MS
            File(context.cacheDir, "motion_photos").listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun resolveInfo(properties: Map<String, String>, containerItems: List<Map<String, String>> = emptyList()): MotionPhotoInfo? {
        if (properties[KEY_MOTION_PHOTO] != "1" && properties[KEY_MICRO_VIDEO] != "1") {
            return null
        }

        var videoOffset = properties[KEY_MOTION_PHOTO_OFFSET]?.toLongOrNull()?.takeIf { it > 0L }
        if (videoOffset == null) {
            val motionItem = containerItems.firstOrNull { it["Item:Semantic"] == SEMANTIC_MOTION_PHOTO }
            val length = motionItem?.get("Item:Length")?.toLongOrNull()
            val padding = motionItem?.get("Item:Padding")?.toLongOrNull() ?: 0L
            if (length != null && length > 0L && padding >= 0L && length <= Long.MAX_VALUE - padding) {
                videoOffset = length + padding
            }
        }
        if (videoOffset == null) {
            val motionEntry = properties.entries.firstOrNull { (key, value) ->
                key.endsWith(ITEM_SEMANTIC_SUFFIX) && value == SEMANTIC_MOTION_PHOTO
            }
            if (motionEntry != null) {
                val prefix = motionEntry.key.removeSuffix(ITEM_SEMANTIC_SUFFIX)
                val length = properties["$prefix$ITEM_LENGTH_SUFFIX"]?.toLongOrNull()
                val padding = properties["$prefix$ITEM_PADDING_SUFFIX"]?.toLongOrNull() ?: 0L
                if (length != null && length > 0L && padding >= 0L && length <= Long.MAX_VALUE - padding) {
                    videoOffset = length + padding
                }
            }
        }

        if (videoOffset == null) {
            videoOffset = properties[KEY_MICRO_VIDEO_OFFSET]?.toLongOrNull()?.takeIf { it > 0L }
        }

        val presentationTimestampUs = properties[KEY_MOTION_PHOTO_PTS]?.toLongOrNull()?.takeIf { it >= 0L }
            ?: properties[KEY_MICRO_VIDEO_PTS]?.toLongOrNull()?.takeIf { it >= 0L }
            ?: -1L
        return videoOffset?.let { MotionPhotoInfo(it, presentationTimestampUs) }
    }

    fun videoStart(fileSize: Long, videoOffset: Long): Long? =
        if (fileSize > 0L && videoOffset in 1..fileSize) fileSize - videoOffset else null

    fun hasMp4Ftyp(header: ByteArray, bytesRead: Int = header.size): Boolean =
        bytesRead >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()

    fun extractVideo(
        context: Context,
        uri: Uri,
        info: MotionPhotoInfo,
        outputDirectory: File = File(context.cacheDir, "motion_photos"),
    ): File? {
        outputDirectory.mkdirs()
        val partFile = File(outputDirectory, "${UUID.randomUUID()}.part")
        val outputFile = File(outputDirectory, "${partFile.nameWithoutExtension}.mp4")
        return try {
            val fileSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it > 0L }
                ?: copySourceToSeekableFile(context, uri, outputDirectory)?.let { sourceCopy ->
                    try {
                        return extractVideoFromFile(sourceCopy, info, outputDirectory)
                    } finally {
                        sourceCopy.delete()
                    }
                }
                ?: return null

            val initialStart = videoStart(fileSize, info.videoOffset)
            val resolvedStart = initialStart?.takeIf { sourceHasFtyp(context, uri, it) }
                ?: findSamsungVideoStart(context, uri, fileSize)
                ?: return null
            val byteCount = fileSize - resolvedStart
            if (byteCount <= 8L) {
                return null
            }

            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { input ->
                    FileOutputStream(partFile).channel.use { output ->
                        input.position(resolvedStart)
                        var copied = 0L
                        while (copied < byteCount) {
                            val count = input.transferTo(input.position(), byteCount - copied, output)
                            if (count <= 0L) break
                            input.position(input.position() + count)
                            copied += count
                        }
                        if (copied != byteCount) {
                            throw java.io.IOException("Incomplete Motion Photo extraction")
                        }
                    }
                }
            } ?: return null

            if (!partFile.renameTo(outputFile)) {
                throw java.io.IOException("Unable to publish extracted video")
            }
            outputFile
        } catch (_: Exception) {
            partFile.delete()
            outputFile.delete()
            null
        }
    }

    fun extractVideoFromFile(
        sourceFile: File,
        info: MotionPhotoInfo,
        outputDirectory: File,
    ): File? {
        outputDirectory.mkdirs()
        val fileSize = sourceFile.length()
        val initialStart = videoStart(fileSize, info.videoOffset)
        val resolvedStart = initialStart?.takeIf { sourceHasFtyp(sourceFile, it) }
            ?: findSamsungVideoStart(sourceFile)
            ?: return null
        val partFile = File(outputDirectory, "${UUID.randomUUID()}.part")
        val outputFile = File(outputDirectory, "${partFile.nameWithoutExtension}.mp4")
        return try {
            FileInputStream(sourceFile).channel.use { input ->
                FileOutputStream(partFile).channel.use { output ->
                    input.position(resolvedStart)
                    val byteCount = fileSize - resolvedStart
                    var copied = 0L
                    while (copied < byteCount) {
                        val count = input.transferTo(input.position(), byteCount - copied, output)
                        if (count <= 0L) break
                        input.position(input.position() + count)
                        copied += count
                    }
                    if (copied != byteCount) {
                        throw java.io.IOException("Incomplete Motion Photo extraction")
                    }
                }
            }
            if (!partFile.renameTo(outputFile)) {
                throw java.io.IOException("Unable to publish extracted video")
            }
            outputFile
        } catch (_: Exception) {
            partFile.delete()
            outputFile.delete()
            null
        }
    }

    fun saveVideoToGallery(
        context: Context,
        uri: Uri,
        sourceLabel: String,
        relativeDir: String = Environment.DIRECTORY_MOVIES
    ): Uri? {
        val info = parseInfo(context, uri) ?: return null
        val tmpFile = extractVideo(context, uri, info) ?: return null
        var insertedUri: Uri? = null
        return try {
            val baseName = sourceLabel.substringBeforeLast('.', sourceLabel)
            val displayName = "${baseName}_motion.mp4"
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val insertUri = resolver.insert(collection, values) ?: return null
            insertedUri = insertUri
            resolver.openOutputStream(insertUri)?.use { output ->
                tmpFile.inputStream().use { input -> input.copyTo(output) }
            } ?: run {
                resolver.delete(insertUri, null, null)
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (resolver.update(insertUri, values, null, null) <= 0) {
                    throw java.io.IOException("Unable to publish extracted video")
                }
            }
            insertedUri = null
            insertUri
        } catch (_: Exception) {
            insertedUri?.let { context.contentResolver.delete(it, null, null) }
            null
        } finally {
            tmpFile.delete()
        }
    }

    fun pathToUri(path: String): Uri =
        if (path.startsWith("content://") || path.startsWith("file://")) Uri.parse(path) else Uri.fromFile(File(path))

    internal fun parseXmpProperties(text: String): Map<String, String> {
        val props = mutableMapOf<String, String>()
        ATTR.findAll(text).forEach { props[it.groupValues[1]] = it.groupValues[2] }
        ELEM.findAll(text).forEach { props[it.groupValues[1]] = it.groupValues[2] }
        return props
    }

    internal fun parseContainerItems(text: String): List<Map<String, String>> {
        val itemTag = Regex("""<Container:Item\b([^>]*?)/?>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return itemTag.findAll(text).map { match ->
            val attrs = linkedMapOf<String, String>()
            ATTR.findAll(match.groupValues[1]).forEach { attrs[it.groupValues[1]] = it.groupValues[2] }
            attrs
        }.toList()
    }

    private fun looksLikeJpegOrHeic(header: ByteArray): Boolean {
        if (header.size < 12) {
            return false
        }
        if ((header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xFF) == 0xD8 &&
            (header[2].toInt() and 0xFF) == 0xFF
        ) {
            return true
        }
        return header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
    }

    private fun findSamsungMarkerOffset(context: Context, uri: Uri): Long? {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        }
        if (pfd != null) {
            pfd.use { descriptor ->
                val size = descriptor.statSize
                if (size <= 0L) {
                    return null
                }
                val start = (size - SAMSUNG_TAIL_SCAN).coerceAtLeast(0L)
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.position(start)
                    val scan = scanSamsungMarker(java.nio.channels.Channels.newInputStream(channel))
                    val markerEnd = scan.lastMarkerEnd ?: return null
                    return size - (start + markerEnd)
                }
            }
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val scan = scanSamsungMarker(input)
            scan.lastMarkerEnd?.let { scan.totalBytes - it }
        }
    }

    private fun copySourceToSeekableFile(context: Context, uri: Uri, outputDirectory: File): File? {
        val partFile = File(outputDirectory, "${UUID.randomUUID()}.source.part")
        val sourceFile = File(outputDirectory, "${partFile.nameWithoutExtension}.source")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partFile).use(input::copyTo)
            } ?: return null
            if (!partFile.renameTo(sourceFile)) throw java.io.IOException("Unable to publish source copy")
            sourceFile
        } catch (_: Exception) {
            partFile.delete()
            sourceFile.delete()
            null
        }
    }

    private fun sourceHasFtyp(context: Context, uri: Uri, start: Long): Boolean {
        val header = ByteArray(12)
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.position(start)
                    val read = channel.read(ByteBuffer.wrap(header))
                    hasMp4Ftyp(header, read)
                }
            } ?: false
        } catch (_: Exception) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                if (!input.skipFully(start)) return@use false
                hasMp4Ftyp(header, input.read(header))
            } ?: false
        }
    }

    private fun sourceHasFtyp(sourceFile: File, start: Long): Boolean {
        val header = ByteArray(12)
        return try {
            FileInputStream(sourceFile).channel.use { channel ->
                channel.position(start)
                hasMp4Ftyp(header, channel.read(ByteBuffer.wrap(header)))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findSamsungVideoStart(context: Context, uri: Uri, fileSize: Long): Long? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            scanSamsungMarker(input).lastMarkerEnd?.takeIf { sourceHasFtyp(context, uri, it) }
        }?.takeIf { it in 0 until fileSize }

    private fun findSamsungVideoStart(sourceFile: File): Long? =
        sourceFile.inputStream().use { input ->
            scanSamsungMarker(input).lastMarkerEnd?.takeIf { sourceHasFtyp(sourceFile, it) }
        }

    private data class SamsungMarkerScan(val totalBytes: Long, val lastMarkerEnd: Long?)

    private fun scanSamsungMarker(input: InputStream): SamsungMarkerScan {
        val marker = SAMSUNG_MARKER
        val overlap = marker.size - 1
        val buffer = ByteArray(64 * 1024 + overlap)
        val first = marker[0]
        var carried = 0
        var total = 0L
        var lastEnd: Long? = null
        while (true) {
            val read = input.read(buffer, carried, buffer.size - carried)
            if (read <= 0) break
            val filled = carried + read
            val bufferStart = total - carried
            var i = 0
            val lastStart = filled - marker.size
            while (i <= lastStart) {
                if (buffer[i] == first) {
                    var matches = true
                    for (index in 1 until marker.size) {
                        if (buffer[i + index] != marker[index]) {
                            matches = false
                            break
                        }
                    }
                    if (matches) lastEnd = bufferStart + i + marker.size
                }
                i++
            }
            total += read
            val keep = minOf(overlap, filled)
            System.arraycopy(buffer, filled - keep, buffer, 0, keep)
            carried = keep
        }
        return SamsungMarkerScan(total, lastEnd)
    }

    private fun InputStream.skipFully(byteCount: Long): Boolean {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (read() >= 0) {
                remaining--
            } else {
                return false
            }
        }
        return true
    }
}
