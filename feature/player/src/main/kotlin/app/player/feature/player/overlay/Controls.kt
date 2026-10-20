package app.player.feature.player.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.component.OverlayChip
import app.player.core.designsystem.component.OverlayIconButton
import app.player.core.designsystem.component.OverlayTitle
import app.player.core.designsystem.component.PlayPauseButton
import app.player.core.designsystem.component.ScrimEdge
import app.player.core.designsystem.component.SeekBar
import app.player.core.designsystem.component.playerScrim
import app.player.core.designsystem.icon.LanthornIcons
import app.player.core.designsystem.theme.PlayerColors
import app.player.core.designsystem.theme.tabular
import app.player.feature.player.R
import app.player.feature.player.icon.PlayerIcons
import app.player.feature.player.time

/** Everything the overlay draws. Built by the player screen from engine state and settings. */
@Immutable
data class ControlsUi(
    val title: String,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** HW, HW+ or SW (US-35.1). */
    val decoder: String,
    /** Shown only when not 1.0x (US-23.3). */
    val speed: String?,
    /** Right-hand seek row text, total or "-remaining" (US-16.4). */
    val endTime: String,
    val showRemaining: Boolean,
    /** Battery and clock, or null when that setting is off (US-60.3). */
    val status: StatusUi?,
    /** Content descriptions of the subtitle and audio top-bar buttons; null hides the button. */
    val subtitleButton: String?,
    val audioButton: String?,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val canLock: Boolean,
    /** Chapter starts as fractions of the duration, drawn as ticks (US-27.1). */
    val marks: List<Float> = emptyList(),
    /** A-B points as fractions, drawn while set (US-25.2). */
    val abA: Float? = null,
    val abB: Float? = null,
)

@Immutable
data class StatusUi(val batteryPercent: Int, val clock: String)

/** What the overlay's buttons do. */
class ControlsActions(
    val back: () -> Unit,
    val togglePause: () -> Unit,
    val scrub: (Float) -> Unit,
    val seek: (Float) -> Unit,
    val toggleRemaining: () -> Unit,
    val openDecoder: () -> Unit,
    val openSubtitles: () -> Unit,
    val openAudio: () -> Unit,
    val openMenu: () -> Unit,
    val lock: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    val rotate: () -> Unit,
    val fit: () -> Unit,
)

/**
 * The player overlay (US-16.1): top bar, seek row, bottom bar, placed where MX users expect them
 * (recon/ux-map.md, Player) with the look from docs/design.md. Stateless.
 */
@Composable
fun Controls(ui: ControlsUi, actions: ControlsActions, modifier: Modifier = Modifier) {
    // Controls stay clear of system bars and cutouts; the scrims run to the screen edge.
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Box(modifier.fillMaxSize()) {
        TopBar(
            ui,
            actions,
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .playerScrim(ScrimEdge.Top)
                .windowInsetsPadding(safe)
                .padding(start = 4.dp, end = 4.dp, bottom = 32.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .playerScrim(ScrimEdge.Bottom)
                .windowInsetsPadding(safe)
                .padding(top = 32.dp, start = 4.dp, end = 4.dp, bottom = 4.dp),
        ) {
            SeekRow(ui, actions)
            BottomBar(ui, actions)
        }
    }
}

@Composable
private fun TopBar(ui: ControlsUi, actions: ControlsActions, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OverlayIconButton(LanthornIcons.Back, stringResource(R.string.player_back), actions.back, Modifier.testTag("back"))
        OverlayTitle(ui.title, Modifier.weight(1f).padding(horizontal = 4.dp).testTag("title"))
        ui.status?.let { StatusText(it, Modifier.padding(horizontal = 8.dp)) }
        ui.speed?.let {
            val desc = stringResource(R.string.player_speed_badge, it)
            Text(
                it,
                color = PlayerColors.Content,
                style = MaterialTheme.typography.labelLarge.tabular(),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .semantics { contentDescription = desc }
                    .testTag("speed-badge"),
            )
        }
        val decoderDesc = stringResource(R.string.player_decoder_badge, ui.decoder)
        OverlayChip(
            ui.decoder,
            selected = false,
            onClick = actions.openDecoder,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = decoderDesc }
                .testTag("decoder-badge"),
        )
        ui.audioButton?.let {
            OverlayIconButton(LanthornIcons.Audio, it, actions.openAudio, Modifier.testTag("audio-button"))
        }
        ui.subtitleButton?.let {
            OverlayIconButton(LanthornIcons.Subtitles, it, actions.openSubtitles, Modifier.testTag("subtitle-button"))
        }
        OverlayIconButton(LanthornIcons.More, stringResource(R.string.player_more), actions.openMenu, Modifier.testTag("more"))
    }
}

/** Battery percentage and clock (US-60.3, US-60.4). */
@Composable
internal fun StatusText(status: StatusUi, modifier: Modifier = Modifier) {
    val battery = stringResource(R.string.player_battery, status.batteryPercent)
    Text(
        "${status.batteryPercent}%  ${status.clock}",
        color = PlayerColors.ContentSecondary,
        style = MaterialTheme.typography.labelLarge.tabular(),
        modifier = modifier
            .semantics { contentDescription = "$battery, ${status.clock}" }
            .testTag("status"),
    )
}

@Composable
private fun SeekRow(ui: ControlsUi, actions: ControlsActions) {
    val duration = ui.durationMs.coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
        val elapsed = time(ui.positionMs)
        val elapsedDesc = stringResource(R.string.player_elapsed, elapsed)
        Text(
            elapsed,
            color = PlayerColors.Content,
            style = MaterialTheme.typography.labelLarge.tabular(),
            modifier = Modifier.semantics { contentDescription = elapsedDesc }.testTag("elapsed"),
        )
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            SeekBar(
                position = ui.positionMs.toFloat() / duration,
                onScrub = actions.scrub,
                onSeek = actions.seek,
                contentDescription = stringResource(R.string.player_seek_bar),
                modifier = Modifier.testTag("seek-bar"),
            )
            if (ui.marks.isNotEmpty() || ui.abA != null) SeekMarks(ui.marks, ui.abA, ui.abB, Modifier.matchParentSize())
        }
        val endDesc = stringResource(
            if (ui.showRemaining) R.string.player_remaining else R.string.player_total,
            ui.endTime,
        )
        Text(
            ui.endTime,
            color = PlayerColors.Content,
            style = MaterialTheme.typography.labelLarge.tabular(),
            textAlign = TextAlign.End,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .widthIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = actions.toggleRemaining)
                .semantics { contentDescription = endDesc }
                .testTag("end-time"),
        )
    }
}

@Composable
private fun BottomBar(ui: ControlsUi, actions: ControlsActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OverlayIconButton(
            LanthornIcons.Lock,
            stringResource(R.string.player_lock),
            actions.lock,
            Modifier.testTag("lock"),
            enabled = ui.canLock,
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OverlayIconButton(
                PlayerIcons.SkipPrevious,
                stringResource(R.string.player_previous),
                actions.previous,
                Modifier.testTag("previous"),
                enabled = ui.hasPrevious,
            )
            PlayPauseButton(
                playing = ui.playing,
                onToggle = actions.togglePause,
                contentDescription = stringResource(if (ui.playing) R.string.player_pause else R.string.player_play),
                size = 56.dp,
                modifier = Modifier.testTag("play-pause"),
            )
            OverlayIconButton(
                PlayerIcons.SkipNext,
                stringResource(R.string.player_next),
                actions.next,
                Modifier.testTag("next"),
                enabled = ui.hasNext,
            )
        }
        Spacer(Modifier.weight(1f))
        OverlayIconButton(PlayerIcons.Rotate, stringResource(R.string.player_rotate), actions.rotate, Modifier.testTag("rotate"))
        OverlayIconButton(PlayerIcons.Fit, stringResource(R.string.player_fit), actions.fit, Modifier.testTag("fit"))
    }
}

/**
 * Chapter ticks and the A-B range over the seek bar. Draws only; touches go through to the bar.
 * Ticks are black 60% so they read on both the track and the progress.
 */
@Composable
private fun SeekMarks(marks: List<Float>, a: Float?, b: Float?, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.testTag("seek-marks")) {
        val cy = size.height / 2
        val tick = 2.dp.toPx()
        val tall = 12.dp.toPx()
        marks.forEach { f ->
            val x = size.width * f.coerceIn(0f, 1f)
            drawRect(PlayerColors.Scrim, Offset(x - tick / 2, cy - tall / 2), Size(tick, tall))
        }
        if (a != null) {
            val xa = size.width * a.coerceIn(0f, 1f)
            val xb = b?.let { size.width * it.coerceIn(0f, 1f) }
            if (xb != null) drawRect(accent.copy(alpha = 0.5f), Offset(xa, cy - tall / 2), Size(xb - xa, tall))
            drawRect(accent, Offset(xa - tick, cy - tall), Size(tick * 2, tall * 2))
            if (xb != null) drawRect(accent, Offset(xb - tick, cy - tall), Size(tick * 2, tall * 2))
        }
    }
}
