package app.player.feature.player

import app.player.core.engine.PlaybackState
import app.player.core.testing.Ears
import app.player.feature.player.settings.PlayerPrefs
import app.player.feature.player.settings.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChromeRulesTest {
    @Ears("US-16.3")
    @Test fun `US-16_3 hides after the delay while playing`() {
        assertEquals(3_000L, autoHideDelay(autoHide = true, delayMs = 3_000, paused = false, talkBack = false, busy = false))
    }

    @Ears("US-16.3")
    @Test fun `US-16_3 never hides while paused`() {
        assertNull(autoHideDelay(autoHide = true, delayMs = 3_000, paused = true, talkBack = false, busy = false))
    }

    @Ears("US-79.1")
    @Test fun `US-79_1 auto-hide off keeps controls up`() {
        assertNull(autoHideDelay(autoHide = false, delayMs = 3_000, paused = false, talkBack = false, busy = false))
    }

    @Ears("US-69.2")
    @Test fun `US-69_2 TalkBack keeps controls until dismissed`() {
        assertNull(autoHideDelay(autoHide = true, delayMs = 3_000, paused = false, talkBack = true, busy = false))
    }

    @Test fun `open panel or scrubbing keeps controls up`() {
        assertNull(autoHideDelay(autoHide = true, delayMs = 3_000, paused = false, talkBack = false, busy = true))
    }

    @Ears("US-79.1")
    @Test fun `US-79_1 delay snaps to 1 to 10 s in half seconds`() {
        assertEquals(1_000L, PlayerPrefs.clampDelay(200))
        assertEquals(10_000L, PlayerPrefs.clampDelay(60_000))
        assertEquals(2_500L, PlayerPrefs.clampDelay(2_400))
        assertEquals(3_000L, PlayerPrefs.clampDelay(3_100))
        assertEquals(3_000L, PlayerPrefs().autoHideDelayMs)
        assertTrue(PlayerPrefs().autoHide)
        assertEquals("2.5", seconds(2_500))
        assertEquals("3", seconds(3_000))
    }

    @Ears("US-78.1")
    @Test fun `US-78_1 second press within 2 s leaves`() {
        val back = BackTwice()
        assertFalse(back.press(10_000))
        assertTrue(back.press(11_999))
    }

    @Ears("US-78.1")
    @Test fun `US-78_1 second press after 2 s only arms again`() {
        val back = BackTwice()
        assertFalse(back.press(10_000))
        assertFalse(back.press(12_001))
        assertTrue(back.press(13_000))
    }

    @Ears("US-78")
    @Test fun `US-78 defaults off`() {
        assertFalse(PlayerPrefs().backTwice)
    }

    @Ears("US-16.4")
    @Test fun `US-16_4 end time shows total or remaining`() {
        val s = PlaybackState(positionMs = 65_000, durationMs = 3_725_000)
        assertEquals("1:02:05", endTime(s, remaining = false))
        assertEquals("-1:01:00", endTime(s, remaining = true))
        assertFalse(PlayerPrefs().showRemaining)
    }

    @Ears("US-23.3")
    @Test fun `US-23_3 speed label only off 1x`() {
        assertNull(speedLabel(1.0))
        assertNull(speedLabel(null))
        assertEquals("1.5x", speedLabel(1.5))
        assertEquals("0.25x", speedLabel(0.25))
        assertEquals("2x", speedLabel(2.0))
    }

    @Ears("US-60")
    @Test fun `US-60 defaults`() {
        assertTrue(PlayerPrefs().statusInTopBar)
        assertFalse(PlayerPrefs().keepStatusVisible)
        assertFalse(PlayerPrefs().keepScreenOnPaused)
    }

    @Test fun `title drops folder and extension`() {
        assertEquals("Holiday 2024", titleOf("file:///sdcard/Movies/Holiday%202024.mkv"))
        assertEquals("clip", titleOf("/storage/emulated/0/clip.mp4"))
        assertEquals("", titleOf(null))
    }
}
