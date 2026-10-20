package app.player.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ripple
import androidx.graphics.shapes.Morph
import app.player.core.designsystem.icon.LanthornIcons
import app.player.core.designsystem.theme.BrandPolygon

/**
 * Centre play/pause. Cookie while playing, morphs to a circle while paused.
 * Colours come from the theme's primary, so wrap the overlay in LanthornPlayerTheme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayPauseButton(
    playing: Boolean,
    onToggle: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
) {
    val morph = remember { Morph(BrandPolygon, MaterialShapes.Circle) }
    val progress by animateFloatAsState(
        targetValue = if (playing) 0f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "play-pause-morph",
    )
    val shape = remember(progress) { MorphShape(morph, progress) }
    Box(
        modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = size / 2),
                onClick = onToggle,
            )
            .semantics {
                role = Role.Button
                stateDescription = if (playing) "playing" else "paused"
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) LanthornIcons.Pause else LanthornIcons.Play,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}

/** A [Morph] at a fixed progress, scaled to the layout size. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path: Path = morph.toPath(progress)
        // Material shapes are defined in a unit square centred on (0.5, 0.5).
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}
