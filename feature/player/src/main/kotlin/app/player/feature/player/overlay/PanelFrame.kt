package app.player.feature.player.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.player.core.designsystem.theme.PlayerMotion

/**
 * Where a panel sits over the video (agreed with subs-audio in work/questions.md): a side sheet on
 * the right in landscape, a bottom sheet of at most half the height in portrait. No dim scrim, so
 * the video stays visible beside the panel and a style panel previews live. A tap outside or back
 * closes it.
 */
@Composable
internal fun PanelFrame(
    visible: Boolean,
    panelId: String?,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = visible, onBack = onClose)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (visible) {
            // Touches outside the panel close it; they never reach the video gestures.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("panel-outside")
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            )
        }
        // Side sheet: clear of the navigation bar and a side cutout. Bottom sheet: navigation bar
        // only; a top camera cutout is no reason to pad its content.
        val insets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        if (landscape) {
            AnimatedVisibility(
                visible,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(PlayerMotion.overlayIn()) { it },
                exit = slideOutHorizontally(PlayerMotion.overlayOut()) { it },
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(min(400.dp, maxWidth * 0.45f))
                        .testTag("panel-${panelId ?: ""}"),
                ) {
                    Box(Modifier.windowInsetsPadding(insets)) { content() }
                }
            }
        } else {
            AnimatedVisibility(
                visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(PlayerMotion.overlayIn()) { it },
                exit = slideOutVertically(PlayerMotion.overlayOut()) { it },
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * 0.5f)
                        .testTag("panel-${panelId ?: ""}"),
                ) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) { content() }
                }
            }
        }
    }
}
