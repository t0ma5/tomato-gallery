package tomato.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DuplicateHasherTest {
    @Test
    fun sameBytesSameFingerprint() {
        val first = ByteArray(100) { 1 }
        val a = DuplicateHasher.fingerprintBytes(100, first, null)
        val b = DuplicateHasher.fingerprintBytes(100, first, null)
        assertEquals(a, b)
    }

    @Test
    fun differentSizeDifferentFingerprint() {
        val first = ByteArray(100) { 1 }
        val a = DuplicateHasher.fingerprintBytes(100, first, null)
        val b = DuplicateHasher.fingerprintBytes(200, first, null)
        assertFalse(a == b)
    }

    @Test
    fun lastChunkChangesHash() {
        val first = ByteArray(64) { 2 }
        val lastA = ByteArray(64) { 3 }
        val lastB = ByteArray(64) { 4 }
        val a = DuplicateHasher.fingerprintBytes(10000, first, lastA)
        val b = DuplicateHasher.fingerprintBytes(10000, first, lastB)
        assertTrue(a != b)
    }

    @Test
    fun keepOrderPrefersOldestThenShortestPath() {
        val stamps = mapOf("/a/long/name.jpg" to 100L, "/a/b.jpg" to 100L, "/z/new.jpg" to 50L, "/y/unknown.jpg" to 0L)
        val ordered = DuplicateHasher.orderForKeeping(stamps.keys.toList()) { stamps.getValue(it) }
        assertEquals(listOf("/z/new.jpg", "/a/b.jpg", "/a/long/name.jpg", "/y/unknown.jpg"), ordered)
    }

    @Test
    fun sameSizeSameEdgesDifferentMiddleIsNotADuplicate() {
        val dir = Files.createTempDirectory("dups").toFile()
        try {
            val size = 64 * 1024
            val a = ByteArray(size) { 7 }
            val b = a.copyOf().also { it[size / 2] = 8 }
            val fileA = File(dir, "a.bin").apply { writeBytes(a) }
            val fileB = File(dir, "b.bin").apply { writeBytes(b) }
            val fileC = File(dir, "c.bin").apply { writeBytes(a) }
            val groups = DuplicateHasher.group(listOf(fileA, fileB, fileC).map { it.absolutePath to 0L })
            assertEquals(1, groups.size)
            assertEquals(setOf(fileA.absolutePath, fileC.absolutePath), groups.first().paths.toSet())
        } finally {
            dir.deleteRecursively()
        }
    }
}
