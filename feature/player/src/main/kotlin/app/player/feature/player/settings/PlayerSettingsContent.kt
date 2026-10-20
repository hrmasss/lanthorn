package app.player.feature.player.settings

import androidx.compose.foundation.layout.Column
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
    Column(modifier.verticalScroll(rememberScrollState()).testTag("player-settings")) {
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
    }
}

/** 3000 -> "3", 2500 -> "2.5". */
internal fun seconds(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000}" else "${ms / 1000}.${ms % 1000 / 100}"
