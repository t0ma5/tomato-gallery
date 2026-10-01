package tomato.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
