package app.player.feature.player.gesture

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.component.ReadoutPill
import app.player.core.designsystem.icon.LanthornIcons
import app.player.core.designsystem.theme.PlayerColors
import app.player.core.designsystem.theme.tabular
import app.player.feature.player.overlay.speedText
import app.player.feature.player.time

/**
 * Gesture feedback (docs/design.md, gesture readouts): centred pill for seek, level, zoom and
 * speed; a "2x" chip at the top for fast play; the skip total on the tapped side.
 */
@Composable
fun GestureReadout(readout: Readout?, onResetSpeed: () -> Unit, modifier: Modifier = Modifier) {
    // A plain Box: a subcomposition (BoxWithConstraints) inside the exiting AnimatedVisibility
    // corrupted the slot table on the hosted emulator.
    Box(modifier.fillMaxSize()) {
        when (readout) {
            null -> Unit
            is Readout.Seek -> ReadoutPill(
                time(readout.targetMs),
                Modifier.align(Alignment.Center).testTag("readout-seek"),
                detail = (if (readout.deltaMs >= 0) "+" else "-") + time(kotlin.math.abs(readout.deltaMs)),
            )
            is Readout.Level -> LevelReadout(readout, Modifier.align(Alignment.Center))
            is Readout.Zoom -> ReadoutPill("${readout.percent}%", Modifier.align(Alignment.Center).testTag("readout-zoom"))
            is Readout.Speed -> if (readout.top) {
                Text(
                    speedText(readout.speed) + "x",
                    style = MaterialTheme.typography.titleMedium.tabular(),
                    color = PlayerColors.Content,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                        .background(PlayerColors.Scrim, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("readout-fast"),
                )
            } else {
                // US-76.3: double tap the readout to go back to 1x.
                ReadoutPill(
                    speedText(readout.speed) + "x",
                    Modifier
                        .align(Alignment.Center)
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onResetSpeed() }) }
                        .testTag("readout-speed"),
                )
            }
            is Readout.Skip -> Box(
                Modifier
                    .align(if (readout.forward) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(1f / 3)
                    .background(PlayerColors.Scrim.copy(alpha = 0.3f))
                    .testTag("readout-skip"),
                contentAlignment = Alignment.Center,
            ) {
                ReadoutPill((if (readout.forward) "+" else "-") + "${readout.seconds}s")
            }
            is Readout.PlayPause -> Box(
                Modifier
                    .align(Alignment.Center)
                    .background(PlayerColors.Scrim, CircleShape)
                    .padding(20.dp)
                    .testTag("readout-playpause"),
            ) {
                Icon(if (readout.playing) LanthornIcons.Play else LanthornIcons.Pause, null, tint = PlayerColors.Content)
            }
        }
    }
}

/** Value with a vertical level bar beside it (docs/design.md). */
@Composable
private fun LevelReadout(level: Readout.Level, modifier: Modifier) {
    Row(
        modifier
            .background(PlayerColors.Scrim, MaterialTheme.shapes.extraLarge)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("readout-level"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            if (level.kind == LevelKind.Brightness) LanthornIcons.Brightness else LanthornIcons.Volume,
            null,
            tint = PlayerColors.Content,
        )
        Box(Modifier.width(6.dp).height(96.dp).background(PlayerColors.Track, CircleShape)) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(level.percent.coerceIn(0, 100) / 100f)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            // Boost past 100% in a second colour over the full bar (US-49.1).
            if (level.percent > 100) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight((level.percent - 100).coerceAtMost(100) / 100f)
                        .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                        .testTag("readout-level-boost"),
                )
            }
        }
        Text("${level.percent}", style = MaterialTheme.typography.displaySmall.tabular(), color = PlayerColors.Content)
    }
}
