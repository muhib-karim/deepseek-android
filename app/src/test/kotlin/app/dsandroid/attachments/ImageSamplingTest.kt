package app.dsandroid.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSamplingTest {
    @Test fun keepsSmallImagesAndBoundsLargeOrExtremeAspectImages() {
        assertEquals(1, imageSampleSize(1200, 800))
        assertEquals(2, imageSampleSize(4096, 2048))
        assertEquals(4, imageSampleSize(4097, 2048))
        listOf(60_000 to 10, Int.MAX_VALUE to Int.MAX_VALUE).forEach { (width, height) ->
            val sample = imageSampleSize(width, height)
            assertTrue(width.toLong() <= 2048L * sample)
            assertTrue(height.toLong() <= 2048L * sample)
            assertEquals(0, sample and (sample - 1))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUndecodableBounds() { imageSampleSize(-1, 2048) }
}
