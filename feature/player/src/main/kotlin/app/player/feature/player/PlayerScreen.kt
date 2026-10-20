package app.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.engine.DecoderMode
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerEngine
import app.player.core.engine.VideoSurfaceView

/** Bare player: video, tap to show controls, play/pause, seek bar, decoder picker, info line. */
@Composable
fun PlayerScreen(
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    /** Hoisted so the bench can open and close the overlay like a viewer would (NF-3). */
    controlsState: MutableState<Boolean> = remember { mutableStateOf(true) },
) {
    val state by engine.state.collectAsStateWithLifecycle()
    var controls by controlsState
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        AndroidView(
            factory = { VideoSurfaceView(it).apply { this.engine = engine } },
            update = { it.engine = engine },
            modifier = Modifier.fillMaxSize(),
        )
        if (controls) Controls(state, engine, Modifier.align(Alignment.BottomCenter))
        state.error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).testTag("player-error"))
        }
    }
}

@Composable
private fun Controls(state: PlaybackState, engine: PlayerEngine, modifier: Modifier) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTo by remember { mutableFloatStateOf(0f) }
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(infoLine(state), color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("player-info"))
        val duration = state.durationMs.coerceAtLeast(1)
        Slider(
            value = if (scrubbing) scrubTo else state.positionMs.toFloat() / duration,
            onValueChange = {
                scrubbing = true
                scrubTo = it
                engine.seekTo((it * duration).toLong(), exact = false)
            },
            onValueChangeFinished = {
                engine.seekTo((scrubTo * duration).toLong(), exact = true)
                scrubbing = false
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = engine::togglePause, modifier = Modifier.testTag("play-pause")) {
                Text(if (state.paused) "Play" else "Pause")
            }
            Text("${time(state.positionMs)} / ${time(state.durationMs)}", color = Color.White)
            DecoderMode.entries.forEach { mode ->
                FilledTonalButton(
                    onClick = { engine.setDecoder(mode) },
                    enabled = mode != state.decoderMode,
                    modifier = Modifier.testTag("decoder-${mode.name}"),
                ) { Text(mode.label) }
            }
        }
    }
}

private val DecoderMode.label get() = when (this) {
    DecoderMode.AUTO -> "Auto"
    DecoderMode.HW -> "HW"
    DecoderMode.HW_PLUS -> "HW+"
    DecoderMode.SW -> "SW"
}

/** The info panel line NF-5 asks for: which decoder is running, plus stream basics. */
internal fun infoLine(s: PlaybackState): String = buildString {
    append("Decoder ").append(s.activeDecoder.label)
    s.video?.let { append(" · ").append(it.codec).append(' ').append(it.width).append('x').append(it.height) }
    s.video?.takeIf { it.fps > 0 }?.let { append(" · %.2f fps".format(it.fps)) }
    append(" · dropped ").append(s.droppedFrames)
    s.firstFrameMs?.let { append(" · first frame ").append(it).append(" ms") }
}

internal fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
