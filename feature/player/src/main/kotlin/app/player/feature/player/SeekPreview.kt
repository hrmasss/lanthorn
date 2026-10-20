package app.player.feature.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.player.core.engine.PlayerEngine
import app.player.core.engine.VideoSurfaceView

/**
 * Holds the seek-preview engine for one file (US-30.1). Create it when the file opens so the
 * preview is loaded before the first drag; release it with the screen.
 */
class SeekPreviewState(val engine: PlayerEngine) {
    /** Shows the frame nearest [positionMs] (keyframe), newest target wins. */
    fun show(positionMs: Long) = engine.scrub(positionMs)
}

@Composable
fun rememberSeekPreview(uri: String?): SeekPreviewState? {
    if (uri == null) return null
    val context = LocalContext.current
    val state = remember(uri) { SeekPreviewState(PlayerEngine.preview(context)) }
    LaunchedEffect(state) { state.engine.open(Uri.parse(uri)) }
    DisposableEffect(state) { onDispose { state.engine.release() } }
    return state
}

/** The preview frame, at least 160 dp wide, with the target time under it. */
@Composable
fun SeekPreview(state: SeekPreviewState, targetMs: Long, modifier: Modifier = Modifier) {
    LaunchedEffect(targetMs) { state.show(targetMs) }
    Column(modifier.testTag("seek-preview"), horizontalAlignment = Alignment.CenterHorizontally) {
        AndroidView(
            factory = { VideoSurfaceView(it).apply { engine = state.engine } },
            modifier = Modifier.size(width = 192.dp, height = 108.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
        )
        Text(
            time(targetMs),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
