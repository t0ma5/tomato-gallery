package tomato.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class TagInputTest {
    @Test
    fun commaSplitsTwoTags() {
        assertEquals(listOf("vacation", "family"), TagInput.split("vacation, family"))
    }

    @Test
    fun semicolonAndDupes() {
        assertEquals(listOf("a", "b"), TagInput.split("a; b; A"))
    }

    @Test
    fun expandOldCombinedRow() {
        assertEquals(listOf("vacation", "family"), TagInput.expand(listOf("vacation, family")))
    }
}
