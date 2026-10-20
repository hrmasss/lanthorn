package app.player.feature.player.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoToolsTest {
    @Test fun `US-53_1 fit cycles fit, stretch, crop, 100 percent and back`() {
        val seen = generateSequence(FitMode.Fit) { it.next() }.take(5).toList()
        assertEquals(listOf(FitMode.Fit, FitMode.Stretch, FitMode.Crop, FitMode.Original, FitMode.Fit), seen)
    }

    @Test fun `US-55_1 zoom 25 to 400 percent maps to mpv log2`() {
        assertEquals(-2.0, VideoTools.zoomToMpv(VideoTools.clampZoom(0.1)), 1e-9)
        assertEquals(2.0, VideoTools.zoomToMpv(VideoTools.clampZoom(9.0)), 1e-9)
        assertEquals(0.0, VideoTools.zoomToMpv(1.0), 1e-9)
        assertEquals(1.5, VideoTools.zoomFromMpv(VideoTools.zoomToMpv(1.5)), 1e-9)
    }

    @Test fun `US-55_3 pan stops at the picture edge`() {
        // Picture 2000 px wide on a 1000 px screen: 500 px overhang each side = 0.25 of the picture.
        assertEquals(0.25, VideoTools.maxPan(2000.0, 1000.0), 1e-9)
        // Picture smaller than the screen: no pan.
        assertEquals(0.0, VideoTools.maxPan(800.0, 1000.0), 1e-9)
    }

    @Test fun `US-54_1 offers every listed ratio`() {
        assertEquals(
            listOf("default", "1:1", "4:3", "16:9", "16:10", "21:9", "2.35:1", "2.39:1", "5:4"),
            VideoTools.ASPECTS.map { it.first },
        )
    }

    @Test fun `US-57_3 each control runs -100 to +100 in steps of 1`() {
        assertEquals(-100L, VideoTools.clampAdjust(-250))
        assertEquals(100L, VideoTools.clampAdjust(101))
        (-100..100).forEach { assertEquals(it.toLong(), VideoTools.clampAdjust(it)) }
    }

    @Test fun `US-57_3 picture adjust defaults to zero`() {
        assertEquals(PictureAdjust(0, 0, 0, 0), PictureAdjust())
    }
}
