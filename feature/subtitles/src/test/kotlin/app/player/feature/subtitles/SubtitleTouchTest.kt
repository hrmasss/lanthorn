package app.player.feature.subtitles

import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTouchTest {
    private val mpv = FakeMpv()
    private var style = SubtitleStyle()
    private var switches = SubtitleGestureSwitches()
    private val touch = SubtitleTouch(mpv, { style }, { style = it(style) }, { switches })

    // A 2400 x 1080 window with a 2.22:1 picture: 165 px bars top and bottom.
    private val osd = """{"w":2400,"h":1080,"mt":165,"mb":165,"ml":0,"mr":0}"""

    private fun show(text: String) {
        mpv.emit("sub-text", text)
        mpv.emit("osd-dimensions", osd)
    }

    @Ears("US-45.1", "US-45.2", "US-45.3")
    @Test fun `only a touch on shown subtitle text is a subtitle gesture`() {
        show("A line in the middle")
        // Picture 750 px high, margin 4% of it (30 px), font 46/720 of it (48 px): text around y 870.
        assertTrue(touch.hit(1200f, 860f, 2400, 1080))
        assertFalse(touch.hit(1200f, 400f, 2400, 1080))
        assertFalse(touch.hit(100f, 860f, 2400, 1080))
        mpv.emit("sub-text", "")
        assertFalse(touch.hit(1200f, 860f, 2400, 1080))
    }

    @Test fun `the box follows margin, size, alignment and fit to video`() {
        val dims = SubtitleTouch.Osd(2400, 1080, mt = 165, mb = 165)
        val base = SubtitleTouch.textBox("Line", SubtitleStyle(), dims, 0f, 2400, 1080)
        val higher = SubtitleTouch.textBox("Line", SubtitleStyle(bottomMarginPercent = 25f), dims, 0f, 2400, 1080)
        assertTrue(higher.bottom < base.bottom - 150)
        val bigger = SubtitleTouch.textBox("Line", SubtitleStyle(scale = 2f), dims, 0f, 2400, 1080)
        assertTrue(bigger.bottom - bigger.top > 1.8f * (base.bottom - base.top) - 60)
        val left = SubtitleTouch.textBox("Line", SubtitleStyle(align = SubAlign.Left), dims, 0f, 2400, 1080)
        assertTrue(left.left < 100)
        // Fit off: the margin counts from the window's bottom, inside the bar.
        val inBar = SubtitleTouch.textBox("Line", SubtitleStyle(fitToVideo = false, bottomMarginPercent = 0f), dims, 0f, 2400, 1080)
        assertTrue(inBar.bottom > 1080 - 60)
        // Two lines are taller than one.
        val two = SubtitleTouch.textBox("One\nTwo", SubtitleStyle(), dims, 0f, 2400, 1080)
        assertTrue(two.bottom - two.top > base.bottom - base.top + 40)
    }

    @Ears("US-45.1")
    @Test fun `a sideways swipe seeks to the previous or next line (US-45_1)`() {
        touch.step(-1)
        touch.step(1)
        assertEquals(listOf("sub-seek -1", "sub-seek 1"), mpv.calls)
    }

    @Ears("US-45.2")
    @Test fun `a vertical drag moves the margin and keeps it (US-45_2)`() {
        touch.drag(-108f, 1080, end = false) // up by 10% of the view
        assertEquals(14f, style.bottomMarginPercent, 0.01f)
        touch.drag(54f, 1080, end = true)
        assertEquals(9f, style.bottomMarginPercent, 0.01f)
        touch.drag(5000f, 1080, end = true)
        assertEquals(0f, style.bottomMarginPercent, 0.01f)
    }

    @Ears("US-45.3")
    @Test fun `a pinch scales the size between 50 and 300 percent (US-45_3)`() {
        touch.scale(1.5f)
        assertEquals(1.5f, style.scale, 0.001f)
        touch.scale(10f)
        assertEquals(3f, style.scale, 0.001f)
        touch.scale(0.01f)
        assertEquals(0.5f, style.scale, 0.001f)
    }

    @Test fun `switched-off gestures do nothing and nothing is hit when all are off`() {
        show("A line in the middle")
        switches = SubtitleGestureSwitches(swipe = false, drag = false, pinch = false)
        assertFalse(touch.hit(1200f, 860f, 2400, 1080))
        touch.step(1)
        touch.drag(-100f, 1080, end = true)
        touch.scale(2f)
        assertTrue(mpv.calls.isEmpty())
        assertEquals(SubtitleStyle(), style)
    }
}
