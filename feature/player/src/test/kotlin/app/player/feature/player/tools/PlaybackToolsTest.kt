package app.player.feature.player.tools

import app.player.core.testing.Ears
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackToolsTest {
    @Ears("US-23.1")
    @Test fun `US-23_1 speed clamps to 0_25 to 4 and snaps to 0_05`() {
        assertEquals(0.25, PlaybackTools.snapSpeed(0.1), 1e-9)
        assertEquals(4.0, PlaybackTools.snapSpeed(9.0), 1e-9)
        assertEquals(1.25, PlaybackTools.snapSpeed(1.26), 1e-9)
        assertEquals(1.3, PlaybackTools.snapSpeed(1.28), 1e-9)
        listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { assertEquals(it, PlaybackTools.snapSpeed(it), 1e-9) }
    }

    @Ears("US-25.1")
    @Test fun `US-25_1 points are sent as seconds`() {
        assertEquals("12.345", PlaybackTools.seconds(12_345))
        assertEquals("0.000", PlaybackTools.seconds(0))
    }

    @Ears("US-27")
    @Test fun `US-27 chapters parse from mpv json`() {
        val json = """[{"title":"Intro","time":0.0},{"title":"","time":65.5},{"time":120.25}]"""
        val chapters = PlaybackTools.parseChapters(json)
        assertEquals(listOf(Chapter(0, "Intro", 0), Chapter(1, null, 65_500), Chapter(2, null, 120_250)), chapters)
    }

    @Test fun `no or broken chapter list is empty`() {
        assertTrue(PlaybackTools.parseChapters(null).isEmpty())
        assertTrue(PlaybackTools.parseChapters("[]").isEmpty())
        assertTrue(PlaybackTools.parseChapters("not json").isEmpty())
    }
}
