package app.player.feature.playback

import android.media.AudioManager
import android.view.KeyEvent
import app.player.core.testing.Ears
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaKeysTest {
    @Ears("US-31.1")
    @Test fun `one press toggles, two play the next video, three the previous (US-31_1)`() = runTest {
        val runs = mutableListOf<KeyAction>()
        val presses = MultiPress(this) { runs += MediaKeys.presses(it) }

        presses.press()
        advanceTimeBy(MultiPress.WINDOW_MS + 1)
        presses.press(); advanceTimeBy(200); presses.press()
        advanceTimeBy(MultiPress.WINDOW_MS + 1)
        presses.press(); advanceTimeBy(200); presses.press(); advanceTimeBy(200); presses.press()
        runCurrent()

        assertEquals(listOf(KeyAction.Toggle, KeyAction.Next, KeyAction.Previous), runs)
    }

    @Ears("US-31.1")
    @Test fun `presses further apart than the window are separate toggles (US-31_1)`() = runTest {
        val runs = mutableListOf<Int>()
        val presses = MultiPress(this) { runs += it }
        presses.press(); advanceTimeBy(MultiPress.WINDOW_MS + 50)
        presses.press(); advanceTimeBy(MultiPress.WINDOW_MS + 50)
        assertEquals(listOf(1, 1), runs)
    }

    @Ears("US-31.1", "US-31.4")
    @Test fun `next and previous change video, or seek by the step when set (US-31_4)`() {
        assertEquals(KeyAction.Press, MediaKeys.action(KeyEvent.KEYCODE_HEADSETHOOK, false))
        assertEquals(KeyAction.Press, MediaKeys.action(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false))
        assertEquals(KeyAction.Next, MediaKeys.action(KeyEvent.KEYCODE_MEDIA_NEXT, false))
        assertEquals(KeyAction.Previous, MediaKeys.action(KeyEvent.KEYCODE_MEDIA_PREVIOUS, false))
        assertEquals(KeyAction.Forward, MediaKeys.action(KeyEvent.KEYCODE_MEDIA_NEXT, true))
        assertEquals(KeyAction.Back, MediaKeys.action(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true))
        assertEquals(KeyAction.None, MediaKeys.action(KeyEvent.KEYCODE_VOLUME_UP, true))
    }

    @Ears("US-31.3")
    @Test fun `a permanent focus loss pauses and never plays again by itself (US-31_3)`() {
        val focus = FocusPolicy()
        assertEquals(FocusAction.Pause, focus.onChange(AudioManager.AUDIOFOCUS_LOSS, playing = true))
        assertEquals(FocusAction.None, focus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))
    }

    @Ears("US-31.3")
    @Test fun `a short loss such as a call pauses and plays again on gain, unless the viewer acted (US-31_3)`() {
        val focus = FocusPolicy()
        assertEquals(FocusAction.Pause, focus.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = true))
        assertEquals(FocusAction.Play, focus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))

        assertEquals(FocusAction.Pause, focus.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = true))
        focus.viewerActed()
        assertEquals(FocusAction.None, focus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))

        // Already paused by the viewer: a short loss does not start it on gain.
        assertEquals(FocusAction.None, focus.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, playing = false))
        assertEquals(FocusAction.None, focus.onChange(AudioManager.AUDIOFOCUS_GAIN, playing = false))
        assertEquals(FocusAction.None, focus.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, playing = true))
    }
}
