package app.player.feature.player.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.player.feature.player.R
import app.player.feature.player.tools.PlaybackTools
import kotlin.math.roundToInt

/**
 * The Player settings group. Shown inside the player (US-65.3) and, once the library's settings
 * screen lands, listed there too. Changes apply at once; playback never restarts.
 */
@Composable
fun PlayerSettingsContent(prefs: PlayerPrefs, update: ((PlayerPrefs) -> PlayerPrefs) -> Unit, modifier: Modifier = Modifier) {
    // Bottom padding so the last row scrolls clear of the sheet's edge and the navigation bar.
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp).testTag("player-settings")) {
        SectionHeader(stringResource(R.string.settings_player))
        SwitchRow(
            stringResource(R.string.settings_auto_hide),
            prefs.autoHide,
            { on -> update { it.copy(autoHide = on) } },
            summary = stringResource(R.string.settings_auto_hide_summary),
            modifier = Modifier.testTag("set-auto-hide"),
        )
        val steps = ((PlayerPrefs.MAX_DELAY_MS - PlayerPrefs.MIN_DELAY_MS) / PlayerPrefs.DELAY_STEP_MS).toInt() - 1
        SliderRow(
            stringResource(R.string.settings_auto_hide_delay),
            stringResource(R.string.settings_seconds, seconds(prefs.autoHideDelayMs)),
            prefs.autoHideDelayMs.toFloat(),
            PlayerPrefs.MIN_DELAY_MS.toFloat()..PlayerPrefs.MAX_DELAY_MS.toFloat(),
            steps,
            { v -> update { it.copy(autoHideDelayMs = PlayerPrefs.clampDelay(v.toLong())) } },
            enabled = prefs.autoHide,
            modifier = Modifier.testTag("set-auto-hide-delay"),
        )
        SliderRow(
            stringResource(R.string.settings_default_speed),
            "${(prefs.defaultSpeed * 100).roundToInt()}%",
            prefs.defaultSpeed.toFloat(),
            0.25f..4.0f,
            74,
            { v -> update { it.copy(defaultSpeed = PlaybackTools.snapSpeed(v.toDouble())) } },
            modifier = Modifier.testTag("set-default-speed"),
        )
        SwitchRow(
            stringResource(R.string.settings_show_remaining),
            prefs.showRemaining,
            { on -> update { it.copy(showRemaining = on) } },
        )
        SwitchRow(
            stringResource(R.string.settings_screen_on_paused),
            prefs.keepScreenOnPaused,
            { on -> update { it.copy(keepScreenOnPaused = on) } },
        )
        SwitchRow(
            stringResource(R.string.settings_status_top_bar),
            prefs.statusInTopBar,
            { on -> update { it.copy(statusInTopBar = on) } },
        )
        SwitchRow(
            stringResource(R.string.settings_keep_status),
            prefs.keepStatusVisible,
            { on -> update { it.copy(keepStatusVisible = on) } },
            summary = stringResource(R.string.settings_keep_status_summary),
            modifier = Modifier.testTag("set-keep-status"),
        )
        SwitchRow(
            stringResource(R.string.settings_back_twice),
            prefs.backTwice,
            { on -> update { it.copy(backTwice = on) } },
            modifier = Modifier.testTag("set-back-twice"),
        )
        GestureSettings(prefs.gestures) { change -> update { it.copy(gestures = change(it.gestures)) } }
    }
}

/** 3000 -> "3", 2500 -> "2.5". */
internal fun seconds(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000}" else "${ms / 1000}.${ms % 1000 / 100}"

/** US-21: one switch per gesture, the tap action, and the gesture amounts. */
@Composable
private fun GestureSettings(g: GesturePrefs, update: ((GesturePrefs) -> GesturePrefs) -> Unit) {
    SectionHeader(stringResource(R.string.settings_gestures))
    val switches: List<Triple<Int, Boolean, (GesturePrefs, Boolean) -> GesturePrefs>> = listOf(
        Triple(R.string.g_seek, g.seek) { p, v -> p.copy(seek = v) },
        Triple(R.string.g_brightness, g.brightness) { p, v -> p.copy(brightness = v) },
        Triple(R.string.g_volume, g.volume) { p, v -> p.copy(volume = v) },
        Triple(R.string.g_dt_skip, g.doubleTapSkip) { p, v -> p.copy(doubleTapSkip = v) },
        Triple(R.string.g_dt_pause, g.doubleTapPause) { p, v -> p.copy(doubleTapPause = v) },
        Triple(R.string.g_dt_zoom, g.doubleTapZoom) { p, v -> p.copy(doubleTapZoom = v) },
        Triple(R.string.g_long_press, g.longPressFast) { p, v -> p.copy(longPressFast = v) },
        Triple(R.string.g_two_finger_speed, g.twoFingerSpeed) { p, v -> p.copy(twoFingerSpeed = v) },
        Triple(R.string.g_pinch, g.pinchZoom) { p, v -> p.copy(pinchZoom = v) },
        Triple(R.string.g_pan, g.pan) { p, v -> p.copy(pan = v) },
        Triple(R.string.g_sub_swipe, g.subtitleSwipe) { p, v -> p.copy(subtitleSwipe = v) },
        Triple(R.string.g_sub_drag, g.subtitleDrag) { p, v -> p.copy(subtitleDrag = v) },
        Triple(R.string.g_sub_pinch, g.subtitlePinch) { p, v -> p.copy(subtitlePinch = v) },
        Triple(R.string.g_quick_zoom, g.quickZoom) { p, v -> p.copy(quickZoom = v) },
        Triple(R.string.g_remember_brightness, g.rememberBrightness) { p, v -> p.copy(rememberBrightness = v) },
    )
    switches.forEach { (title, on, set) ->
        SwitchRow(stringResource(title), on, { v -> update { set(it, v) } }, modifier = Modifier.testTag("g-$title"))
    }
    SliderRow(
        stringResource(R.string.g_seek_full_width),
        stringResource(R.string.settings_seconds, g.seekFullWidthSec.toString()),
        g.seekFullWidthSec.toFloat(), 10f..600f, 58,
        { v -> update { it.copy(seekFullWidthSec = ((v / 10).roundToInt() * 10).coerceIn(10, 600)) } },
    )
    SliderRow(
        stringResource(R.string.g_skip_step),
        stringResource(R.string.settings_seconds, g.skipStepSec.toString()),
        g.skipStepSec.toFloat(), 5f..60f, 10,
        { v -> update { it.copy(skipStepSec = ((v / 5).roundToInt() * 5).coerceIn(5, 60)) } },
    )
    SliderRow(
        stringResource(R.string.g_fast_speed),
        "${PlaybackTools.snapSpeed(g.fastSpeed)}x",
        g.fastSpeed.toFloat(), 1.5f..4.0f, 9,
        { v -> update { it.copy(fastSpeed = ((v * 4).roundToInt() / 4.0).coerceIn(1.5, 4.0)) } },
    )
    SectionHeader(stringResource(R.string.g_touch_action))
    listOf(
        TouchAction.ToggleControls to R.string.g_touch_toggle,
        TouchAction.Pause to R.string.g_touch_pause,
        TouchAction.ShowThenPause to R.string.g_touch_show_then_pause,
        TouchAction.ShowAndPause to R.string.g_touch_show_and_pause,
    ).forEach { (action, label) ->
        RadioRow(stringResource(label), g.touchAction == action, { update { it.copy(touchAction = action) } }, Modifier.testTag("touch-${action.name}"))
    }
}
