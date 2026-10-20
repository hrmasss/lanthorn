package app.player.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.theme.BrandPolygon
import app.player.core.designsystem.theme.PlayerColors
import app.player.core.designsystem.theme.tabular

/**
 * 16:9 thumbnail frame for list and grid. [image] draws the picture; the frame adds the shape,
 * the duration pill, the resume bar and the unplayed dot.
 */
@Composable
fun VideoThumbnail(
    duration: String,
    modifier: Modifier = Modifier,
    resume: Float? = null,
    unplayed: Boolean = false,
    image: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        image()
        Text(
            duration,
            style = MaterialTheme.typography.labelSmall.tabular(),
            color = PlayerColors.Content,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(PlayerColors.Scrim, CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (unplayed) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
            )
        }
        if (resume != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(resume.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Empty state: the brand cookie, one line, one action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, BrandPolygon.toShape()),
        )
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
