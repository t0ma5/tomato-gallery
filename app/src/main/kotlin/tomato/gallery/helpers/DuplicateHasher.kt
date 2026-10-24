package tomato.gallery.helpers

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

object DuplicateHasher {
    private const val SAMPLE = 8 * 1024

    /**
     * [paths] holds byte-identical files. The first entry is the suggested copy to keep
     * (see [orderForKeeping]); the rest are the suggested deletions.
     */
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

    /** SHA-256 over every byte of the file. Returns null when cancelled. */
    fun fullHash(path: String, isCancelled: () -> Boolean = { false }): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (isCancelled()) {
                    return null
                }
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Deterministic keep order: oldest file first, then the shortest path, then alphabetical.
     * [lastModified] is injectable for tests.
     */
    fun orderForKeeping(paths: List<String>, lastModified: (String) -> Long = { File(it).lastModified() }): ArrayList<String> {
        val stamps = paths.associateWith { lastModified(it).let { ts -> if (ts <= 0L) Long.MAX_VALUE else ts } }
        return ArrayList(paths.sortedWith(compareBy<String>({ stamps[it] }, { it.length }, { it })))
    }

    /**
     * Groups byte-identical files: same size, then same sampled fingerprint, then same full SHA-256.
     * The size from the list is ignored when it is not positive, the real file length is used instead.
     */
    fun group(pathsAndSizes: List<Pair<String, Long>>, isCancelled: () -> Boolean = { false }): List<Group> {
        val bySize = linkedMapOf<Long, ArrayList<String>>()
        pathsAndSizes.distinctBy { it.first }.forEach { (path, _) ->
            if (isCancelled()) {
                return emptyList()
            }
            val file = File(path)
            if (!file.isFile) {
                return@forEach
            }
            val size = file.length()
            if (size <= 0L) {
                return@forEach
            }
            bySize.getOrPut(size) { arrayListOf() }.add(path)
        }

        val result = ArrayList<Group>()
        for ((size, sameSize) in bySize) {
            if (sameSize.size < 2) {
                continue
            }

            val byPartial = linkedMapOf<String, ArrayList<String>>()
            for (path in sameSize) {
                if (isCancelled()) {
                    return emptyList()
                }
                val key = try {
                    fingerprint(path, size)
                } catch (_: Exception) {
                    continue
                }
                byPartial.getOrPut(key) { arrayListOf() }.add(path)
            }

            for (candidates in byPartial.values) {
                if (candidates.size < 2) {
                    continue
                }
                val byFull = linkedMapOf<String, ArrayList<String>>()
                for (path in candidates) {
                    val key = try {
                        fullHash(path, isCancelled)
                    } catch (_: Exception) {
                        null
                    }
                    if (isCancelled()) {
                        return emptyList()
                    }
                    if (key != null) {
                        byFull.getOrPut(key) { arrayListOf() }.add(path)
                    }
                }
                byFull.forEach { (hash, paths) ->
                    if (paths.size > 1) {
                        result.add(Group(hash, orderForKeeping(paths)))
                    }
                }
            }
        }
        return result
    }
}
