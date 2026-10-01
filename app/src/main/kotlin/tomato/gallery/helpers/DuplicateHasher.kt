package tomato.gallery.helpers

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

object DuplicateHasher {
    private const val SAMPLE = 8 * 1024

    data class Group(val fingerprint: String, val paths: ArrayList<String>)

    fun fingerprint(path: String, size: Long): String {
        if (size <= 0L) {
            return "empty:$path"
        }
        val digest = MessageDigest.getInstance("MD5")
        digest.update(size.toString().toByteArray())
        RandomAccessFile(path, "r").use { raf ->
            val first = ByteArray(minOf(SAMPLE.toLong(), size).toInt())
            raf.readFully(first)
            digest.update(first)
            if (size > SAMPLE * 2) {
                raf.seek(size - SAMPLE)
                val last = ByteArray(SAMPLE)
                raf.readFully(last)
                digest.update(last)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun fingerprintBytes(size: Long, first: ByteArray, last: ByteArray?): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(size.toString().toByteArray())
        digest.update(first)
        if (last != null) {
            digest.update(last)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun group(pathsAndSizes: List<Pair<String, Long>>): List<Group> {
        val buckets = linkedMapOf<String, ArrayList<String>>()
        pathsAndSizes.forEach { (path, size) ->
            if (!File(path).isFile) {
                return@forEach
            }
            val key = try {
                fingerprint(path, size)
            } catch (_: Exception) {
                return@forEach
            }
            buckets.getOrPut(key) { arrayListOf() }.add(path)
        }
        return buckets.map { Group(it.key, it.value) }.filter { it.paths.size > 1 }
    }
}
