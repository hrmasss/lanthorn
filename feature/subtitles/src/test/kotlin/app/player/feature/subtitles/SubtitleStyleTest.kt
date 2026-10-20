package app.player.feature.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleStyleTest {
    private fun SubtitleStyle.props() = toMpv().toMap()

    @Test fun `defaults are white bold, shadow, no outline, no box (US-42_2)`() {
        val p = SubtitleStyle().props()
        assertEquals("#FFFFFFFF", p["sub-color"])
        assertEquals("yes", p["sub-bold"])
        assertEquals("0", p["sub-border-size"])
        assertEquals("outline-and-shadow", p["sub-border-style"])
        assertEquals("2", p["sub-shadow-offset"])
        assertEquals("center", p["sub-align-x"])
        assertEquals("no", p["sub-use-margins"])
    }

    @Test fun `outline, box and opacity map to mpv (US-42_1)`() {
        val outlined = SubtitleStyle(outline = true, outlineSize = 4f, outlineColor = 0xFF123456).props()
        assertEquals("4.0", outlined["sub-border-size"])
        assertEquals("#FF123456", outlined["sub-border-color"])
        val boxed = SubtitleStyle(box = true, boxColor = 0xFF000000, boxOpacity = 0.5f).props()
        assertEquals("background-box", boxed["sub-border-style"])
        assertEquals("#80000000", boxed["sub-back-color"])
        assertEquals("0", SubtitleStyle(shadow = false).props()["sub-shadow-offset"])
    }

    @Test fun `alignment and margin 0 to 50 percent, fit to video (US-43)`() {
        val p = SubtitleStyle(align = SubAlign.Left, bottomMarginPercent = 25f, fitToVideo = false).props()
        assertEquals("left", p["sub-align-x"])
        assertEquals("left", p["sub-justify"])
        assertEquals("180", p["sub-margin-y"])
        assertEquals("yes", p["sub-use-margins"])
        assertEquals("360", SubtitleStyle(bottomMarginPercent = 90f).props()["sub-margin-y"])
    }

    @Test fun `scale clamps to 50 to 300 percent`() {
        assertEquals("0.5", SubtitleStyle(scale = 0.1f).props()["sub-scale"])
        assertEquals("3.0", SubtitleStyle(scale = 9f).props()["sub-scale"])
    }

    @Test fun `style survives a save round trip, bad json gives defaults`() {
        val s = SubtitleStyle(font = "Noto Serif", size = 60, color = 0xFFFFEB3B, outline = true, box = true, align = SubAlign.Right, bottomMarginPercent = 12f)
        assertEquals(s, decodeStyle(encodeStyle(s)))
        assertEquals(SubtitleStyle(), decodeStyle("{"))
        assertEquals(SubtitleStyle().copy(size = 30), decodeStyle("""{"size":30,"align":"Nope"}"""))
    }
}
