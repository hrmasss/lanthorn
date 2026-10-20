package app.player.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.player.core.designsystem.theme.PlayerColors

/**
 * Bottom sheet for pickers (tracks, speed, sort). Skips the half-expanded state: these sheets
 * are short, and one swipe should close them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            content()
        }
    }
}

/**
 * Buffering in the player. The shape-morphing indicator, never a spinner. White on a scrim
 * disc so it stays visible over a white frame.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BufferingIndicator(modifier: Modifier = Modifier) {
    ContainedLoadingIndicator(
        modifier = modifier,
        containerColor = PlayerColors.Scrim,
        indicatorColor = PlayerColors.Content,
    )
}
