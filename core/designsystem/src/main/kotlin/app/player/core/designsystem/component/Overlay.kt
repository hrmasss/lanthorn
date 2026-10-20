package app.player.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.theme.PlayerColors
import app.player.core.designsystem.theme.tabular

/** Which edge a [playerScrim] darkens. */
enum class ScrimEdge { Top, Bottom }

/**
 * Black 60% at the edge, fading to clear by [reach] of the height. Keeps white text at
 * 5.7:1 even over a white frame (docs/design.md, Player overlay).
 */
fun Modifier.playerScrim(edge: ScrimEdge, reach: Float = 1f): Modifier = drawBehind {
    val stops = arrayOf(0f to PlayerColors.Scrim, reach to Color.Transparent)
    val brush = when (edge) {
        ScrimEdge.Top -> Brush.verticalGradient(*stops, startY = 0f, endY = size.height)
        ScrimEdge.Bottom -> Brush.verticalGradient(*stops, startY = size.height, endY = 0f)
    }
    drawRect(brush)
}

/** White icon button for the player chrome. 48 dp target, 24 dp glyph. */
@Composable
fun OverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = PlayerColors.Content,
            disabledContentColor = PlayerColors.ContentDisabled,
        ),
    ) {
        Icon(icon, contentDescription)
    }
}

/**
 * Toggle chip for the overlay (decoder, speed, aspect). Outlined white 70%; active fills white
 * with black text.
 */
@Composable
fun OverlayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) PlayerColors.Content else Color.Transparent,
        contentColor = if (selected) Color.Black else PlayerColors.Content,
        border = if (selected) null else BorderStroke(1.dp, PlayerColors.ContentSecondary),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.tabular(),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** Video title in the overlay's top bar. One line, ellipsis at the end. */
@Composable
fun OverlayTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = PlayerColors.Content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Centred readout for a gesture in progress: seek target and delta, volume, brightness. */
@Composable
fun ReadoutPill(
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Box(
        modifier
            .background(PlayerColors.Scrim, CircleShape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.displaySmall.tabular(), color = PlayerColors.Content)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.titleMedium.tabular(), color = PlayerColors.ContentSecondary)
            }
        }
    }
}
