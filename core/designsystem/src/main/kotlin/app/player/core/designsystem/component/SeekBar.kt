package app.player.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.theme.PlayerColors

/**
 * Player seek bar. Track white 30%, buffered white 50%, progress and thumb in primary.
 * The track grows from 4 dp to 12 dp while dragging.
 *
 * [onScrub] fires on every move while the finger is down (drive the preview frame from it);
 * [onSeek] fires once on release with the final position. Positions are fractions 0..1.
 */
@Composable
fun SeekBar(
    position: Float,
    onScrub: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    val track by animateDpAsState(if (dragging) 12.dp else 4.dp, label = "seek-track")
    val thumb by animateDpAsState(if (dragging) 0.dp else 14.dp, label = "seek-thumb")
    val accent = MaterialTheme.colorScheme.primary
    val shown = if (dragging) dragPos else position.coerceIn(0f, 1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { target -> seek(target.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragPos = (it.x / size.width).coerceIn(0f, 1f)
                        scrub(dragPos)
                    },
                    onDragEnd = { dragging = false; seek(dragPos) },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragPos = (change.position.x / size.width).coerceIn(0f, 1f)
                        scrub(dragPos)
                    },
                )
            },
    ) {
        val h = track.toPx()
        val top = (size.height - h) / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(PlayerColors.Track, Offset(0f, top), Size(size.width, h), r)
        drawRoundRect(PlayerColors.Buffered, Offset(0f, top), Size(size.width * buffered.coerceIn(0f, 1f), h), r)
        drawRoundRect(accent, Offset(0f, top), Size(size.width * shown, h), r)
        if (thumb > 0.dp) drawCircle(accent, thumb.toPx() / 2, Offset(size.width * shown, size.height / 2))
    }
}
