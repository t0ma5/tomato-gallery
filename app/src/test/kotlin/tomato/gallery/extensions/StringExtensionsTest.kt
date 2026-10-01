package tomato.gallery.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StringExtensionsTest {
    @Test
    fun parentIncludeAndExcludeMatchPrefixes() {
        val included = mutableSetOf("/storage/emulated/0/DCIM")
        val excluded = mutableSetOf("/storage/emulated/0/Download")
        assertTrue("/storage/emulated/0/DCIM/Camera".isThisOrParentIncluded(included))
        assertFalse("/storage/emulated/0/Pictures".isThisOrParentIncluded(included))
        assertTrue("/storage/emulated/0/Download/foo".isThisOrParentExcluded(excluded))
        assertFalse("/storage/emulated/0/DCIM".isThisOrParentExcluded(excluded))
    }

    @Test
    fun restrictedAndroidFoldersAreDetected() {
        assertTrue("/storage/emulated/0/Android/data/com.foo".isRestrictedAndroidFolder())
        assertTrue("/storage/emulated/0/Android/obb".isRestrictedAndroidFolder())
        assertTrue("/data/data/com.foo".isRestrictedAndroidFolder())
        assertFalse("/storage/emulated/0/DCIM".isRestrictedAndroidFolder())
    }

    @Test
    fun explicitIncludeCanUnhideRestrictedFolder() {
        val included = mutableSetOf("/storage/emulated/0/Android/data/com.foo")
        assertTrue("/storage/emulated/0/Android/data/com.foo".isExplicitlyIncludedRestrictedFolder(included))
        assertFalse("/storage/emulated/0/Android/data/com.bar".isExplicitlyIncludedRestrictedFolder(included))
        assertFalse("/storage/emulated/0/Android/data/com.foo".isExplicitlyIncludedRestrictedFolder(mutableSetOf("/storage/emulated/0")))
    }
}
