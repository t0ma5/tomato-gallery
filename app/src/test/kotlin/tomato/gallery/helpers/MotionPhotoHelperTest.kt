package tomato.gallery.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPhotoHelperTest {
    @Test
    fun microVideoOffset() {
        val xmp = """
            <x:xmpmeta>
              <rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="12345" GCamera:MicroVideoPresentationTimestampUs="1000"/>
            </x:xmpmeta>
        """.trimIndent()
        val info = MotionPhotoHelper.resolveInfo(MotionPhotoHelper.parseXmpProperties(xmp))
        assertNotNull(info)
        assertEquals(12345L, info!!.videoOffset)
        assertEquals(1000L, info.presentationTimestampUs)
    }

    @Test
    fun motionPhotoVideoOffset() {
        val xmp = """
            <x:xmpmeta>
              <rdf:Description GCamera:MotionPhoto="1" GCamera:MotionPhotoVideoOffset="8888" GCamera:MotionPhotoPresentationTimestampUs="50"/>
            </x:xmpmeta>
        """.trimIndent()
        val info = MotionPhotoHelper.resolveInfo(MotionPhotoHelper.parseXmpProperties(xmp))
        assertNotNull(info)
        assertEquals(8888L, info!!.videoOffset)
        assertEquals(50L, info.presentationTimestampUs)
    }

    @Test
    fun containerDirectoryItemsArePerElement() {
        val xmp = """
            <x:xmpmeta>
              <rdf:Description GCamera:MotionPhoto="1">
                <Container:Directory>
                  <rdf:Seq>
                    <rdf:li>
                      <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg" Item:Length="111"/>
                    </rdf:li>
                    <rdf:li>
                      <Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="2222" Item:Padding="8"/>
                    </rdf:li>
                  </rdf:Seq>
                </Container:Directory>
              </rdf:Description>
            </x:xmpmeta>
        """.trimIndent()
        val info = MotionPhotoHelper.resolveInfo(
            MotionPhotoHelper.parseXmpProperties(xmp),
            MotionPhotoHelper.parseContainerItems(xmp)
        )
        assertNotNull(info)
        assertEquals(2230L, info!!.videoOffset)
    }

    @Test
    fun missingMotionFlagReturnsNull() {
        val xmp = """<rdf:Description Item:Semantic="MotionPhoto" Item:Length="10"/>"""
        assertNull(MotionPhotoHelper.resolveInfo(MotionPhotoHelper.parseXmpProperties(xmp)))
    }
}
