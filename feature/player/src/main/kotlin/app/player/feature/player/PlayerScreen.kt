package app.player.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.engine.DecoderMode
import app.player.core.engine.PlaybackState
import app.player.core.engine.PlayerEngine
import app.player.core.engine.VideoSurfaceView
import app.player.core.playerapi.PanelHost
import app.player.core.playerapi.PlayerExtension
import app.player.core.playerapi.PlayerMenuEntry
import app.player.core.playerapi.PlayerPanel

/** Bare player: video, tap to show controls, play/pause, seek bar, decoder picker, info line. */
@Composable
fun PlayerScreen(
    engine: PlayerEngine,
    modifier: Modifier = Modifier,
    /** Hoisted so the bench can open and close the overlay like a viewer would (NF-3). */
    controlsState: MutableState<Boolean> = remember { mutableStateOf(true) },
    extensions: List<PlayerExtension> = emptyList(),
    /** Seek preview on the seek bar (US-30.1, default on). Off: the video itself follows the finger. */
    seekPreview: Boolean = true,
) {
    val state by engine.state.collectAsStateWithLifecycle()
    var controls by controlsState
    val panels = remember(extensions) { extensions.flatMap { it.panels }.associateBy { it.id } }
    val entries = remember(extensions) {
        extensions.flatMap { it.menuEntries }.sortedWith(compareBy({ it.group }, { it.order }))
    }
    var openPanel by remember { mutableStateOf<String?>(null) }
    // US-30.1: the seek preview loads with the file, so the first drag shows frames at once.
    val preview = if (seekPreview) rememberSeekPreview(state.uri) else null
    Box(
        modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        AndroidView(
            factory = { VideoSurfaceView(it).apply { this.engine = engine } },
            update = { it.engine = engine },
            modifier = Modifier.fillMaxSize(),
        )
        if (controls) Controls(state, engine, entries, { openPanel = it }, preview, Modifier.align(Alignment.BottomCenter))
        state.error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).testTag("player-error"))
        }
        openPanel?.let { id -> panels[id] }?.let { panel ->
            PanelSheet(panel, engine, onOpen = { openPanel = it }, onClose = { openPanel = null })
        }
    }
}

/** Hosts a feature's panel. A bottom sheet for now; player-ui owns the final look. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanelSheet(panel: PlayerPanel, engine: PlayerEngine, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val host = remember(panel, engine) {
        object : PanelHost {
            override val engine = engine
            override fun close() = onClose()
            override fun open(panelId: String) = onOpen(panelId)
        }
    }
    ModalBottomSheet(onDismissRequest = onClose, modifier = Modifier.testTag("panel-${panel.id}")) {
        panel.Content(host)
    }
}

@Composable
private fun Controls(
    state: PlaybackState,
    engine: PlayerEngine,
    entries: List<PlayerMenuEntry>,
    onOpenPanel: (String) -> Unit,
    preview: SeekPreviewState?,
    modifier: Modifier,
) {
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
        if (scrubbing && preview != null) {
            SeekPreview(preview, (scrubTo * duration).toLong(), Modifier.align(Alignment.CenterHorizontally))
        }
        Slider(
            value = if (scrubbing) scrubTo else state.positionMs.toFloat() / duration,
            onValueChange = {
                scrubbing = true
                scrubTo = it
                // With a preview the video holds still until release; without, it follows the finger.
                if (preview == null) engine.scrub((it * duration).toLong())
            },
            onValueChangeFinished = {
                engine.endScrub((scrubTo * duration).toLong())
                scrubbing = false
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            entries.forEach { entry ->
                FilledTonalButton(onClick = { onOpenPanel(entry.opensPanel) }, modifier = Modifier.testTag("menu-${entry.id}")) {
                    Text(entry.label())
                }
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
