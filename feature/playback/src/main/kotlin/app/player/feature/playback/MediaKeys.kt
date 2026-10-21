package app.player.feature.playback

import android.media.AudioManager
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a media key or a run of headset presses asks for. */
internal enum class KeyAction { Toggle, Play, Pause, Next, Previous, Forward, Back, Press, None }

/** Media keys, from a headset, Bluetooth, the lock screen or a keyboard (US-31.1, US-31.4, US-28.3). */
internal object MediaKeys {
    fun action(keyCode: Int, nextPreviousSeek: Boolean): KeyAction = when (keyCode) {
        // The headset's one button: counted, see [MultiPress].
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> KeyAction.Press
        KeyEvent.KEYCODE_MEDIA_PLAY -> KeyAction.Play
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> KeyAction.Pause
        KeyEvent.KEYCODE_MEDIA_NEXT -> if (nextPreviousSeek) KeyAction.Forward else KeyAction.Next
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (nextPreviousSeek) KeyAction.Back else KeyAction.Previous
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> KeyAction.Forward
        KeyEvent.KEYCODE_MEDIA_REWIND -> KeyAction.Back
        else -> KeyAction.None
    }

    /** One press toggles, two play the next video, three the previous (US-31.1). */
    fun presses(count: Int): KeyAction = when (count) {
        1 -> KeyAction.Toggle
        2 -> KeyAction.Next
        else -> KeyAction.Previous
    }
}

/**
 * Counts headset presses: a run ends [windowMs] after its last press, or at the third press.
 * Main thread.
 */
internal class MultiPress(
    private val scope: CoroutineScope,
    private val windowMs: Long = WINDOW_MS,
    private val onRun: (Int) -> Unit,
) {
    private var count = 0
    private var pending: Job? = null

    fun press() {
        count++
        pending?.cancel()
        pending = null
        if (count >= 3) return end()
        pending = scope.launch {
            delay(windowMs)
            pending = null
            end()
        }
    }

    private fun end() {
        val n = count
        count = 0
        onRun(n)
    }

    companion object {
        /** Gap between presses that still counts as one run. */
        const val WINDOW_MS = 400L
    }
}

/** What an audio focus change asks the player to do. */
internal enum class FocusAction { Pause, Play, None }

/**
 * Audio focus (US-31.3). A permanent loss pauses for good; a short loss (a call, a navigation
 * prompt) pauses and plays again when focus comes back, unless the viewer acted meanwhile. A loss
 * that allows ducking is left to the system, which lowers the volume.
 */
internal class FocusPolicy {
    private var playOnGain = false

    fun onChange(change: Int, playing: Boolean): FocusAction = when (change) {
        AudioManager.AUDIOFOCUS_LOSS -> {
            playOnGain = false
            if (playing) FocusAction.Pause else FocusAction.None
        }
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
            if (playing) playOnGain = true
            if (playing) FocusAction.Pause else FocusAction.None
        }
        AudioManager.AUDIOFOCUS_GAIN -> if (playOnGain) {
            playOnGain = false
            FocusAction.Play
        } else FocusAction.None
        else -> FocusAction.None
    }

    /** The viewer played or paused: a later gain does not override them. */
    fun viewerActed() {
        playOnGain = false
    }
}
