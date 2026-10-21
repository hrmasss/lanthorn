package app.player.feature.library.network

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.player.core.designsystem.theme.PlayerColors
import app.player.core.designsystem.theme.tabular
import app.player.core.engine.PlayerEngine
import app.player.core.engine.PropertyType
import app.player.feature.library.R

/**
 * Network state over the video, for streams only: buffering progress while a stream loads or
 * stalls (US-61.1), and on failure the reason with a retry (US-61.4). Draws nothing for local files.
 */
@Composable
internal fun NetworkOverlay(engine: PlayerEngine) {
    val state by engine.state.collectAsStateWithLifecycle()
    val uri = state.uri
    if (uri == null || !isNetworkUrl(uri)) return

    // mpv's warn and error lines since this stream was opened, for the failure reason.
    val lines = remember(uri) { mutableStateListOf<String>() }
    LaunchedEffect(engine, uri) {
        engine.messages.collect { m -> if (lines.size < 64) lines += "[${m.prefix}] ${m.text}" }
    }
    val percent by remember(engine) { engine.observe("cache-buffering-state", PropertyType.Int64) }
        .collectAsStateWithLifecycle()
    // Each stall's start and end once the stream plays, so a run can count rebuffers and their length (US-62.2).
    LaunchedEffect(state.loaded, state.buffering) {
        if (state.loaded) Log.i("LibraryNet", "stalled=${state.buffering} at ${state.positionMs} ms")
    }

    Box(Modifier.fillMaxSize()) {
        val error = state.error
        when {
            error != null -> FailureCard(classifyFailure(lines), onRetry = {
                lines.clear()
                engine.open(uri.toUri())
            }, Modifier.align(Alignment.Center).padding(top = 160.dp))
            !state.loaded || state.buffering -> {
                val p = percent
                // One line per shown value, so a test reads what the viewer saw from logcat.
                LaunchedEffect(state.loaded, p) { Log.i("LibraryNet", "progress loaded=${state.loaded} buffering=$p%") }
                Text(
                    when {
                        p == null || !state.loaded -> stringResource(R.string.stream_connecting)
                        // mpv reports 100 when it is not counting; a percent is only shown while it climbs.
                        p in 0..99 -> stringResource(R.string.stream_buffering, p)
                        else -> stringResource(R.string.stream_buffering_plain)
                    },
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    color = PlayerColors.Content,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 24.dp)
                        .background(PlayerColors.Scrim, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("stream-buffering"),
                )
            }
        }
    }
}

@Composable
private fun FailureCard(failure: StreamFailure, onRetry: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = 360.dp)
            .background(PlayerColors.Scrim, MaterialTheme.shapes.large)
            .padding(20.dp)
            .testTag("stream-failure"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(
                when (failure) {
                    StreamFailure.NotFound -> R.string.stream_fail_not_found
                    StreamFailure.Timeout -> R.string.stream_fail_timeout
                    StreamFailure.Unsupported -> R.string.stream_fail_unsupported
                    StreamFailure.Other -> R.string.stream_fail_other
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = PlayerColors.Content,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onRetry, modifier = Modifier.testTag("stream-retry")) {
            Text(stringResource(R.string.stream_retry))
        }
    }
}
