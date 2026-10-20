package app.player.feature.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Folder: Material Symbols Rounded, weight 400, 24 px. Copyright Google, Apache License 2.0
// (https://github.com/google/material-design-icons). The others are plain shapes drawn here.
// Glyphs LanthornIcons lacks; proposed to @brand.

internal object LibraryIcons {
    val Folder: ImageVector by lazy { symbol("Folder", "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h207q16 0 30.5 6t25.5 17l57 57h320q33 0 56.5 23.5T880-640v400q0 33-23.5 56.5T800-160H160Z") }

    /** Four tiles. */
    val Grid: ImageVector by lazy {
        shapes("Grid") {
            roundRect(3f, 3f, 10.5f, 10.5f)
            roundRect(13.5f, 3f, 21f, 10.5f)
            roundRect(3f, 13.5f, 10.5f, 21f)
            roundRect(13.5f, 13.5f, 21f, 21f)
        }
    }

    /** Three rows, each a small tile and a bar. */
    val List: ImageVector by lazy {
        shapes("List") {
            for (top in listOf(4f, 10.5f, 17f)) {
                roundRect(3f, top, 7f, top + 3f, 1f)
                roundRect(9f, top, 21f, top + 3f, 1f)
            }
        }
    }

    /** A frame with a play triangle: every video in one list. */
    val AllVideos: ImageVector by lazy {
        shapes("AllVideos", PathFillType.EvenOdd) {
            roundRect(2f, 4f, 22f, 20f, 3f)
            // Even-odd fill leaves the play triangle as a hole.
            moveTo(10f, 8.5f)
            lineTo(10f, 15.5f)
            lineTo(15.5f, 12f)
            close()
        }
    }

    /** Three bars, shorter going down: sort. */
    val Sort: ImageVector by lazy {
        shapes("Sort") {
            roundRect(3f, 5f, 21f, 7.5f, 1.25f)
            roundRect(3f, 10.75f, 15f, 13.25f, 1.25f)
            roundRect(3f, 16.5f, 9f, 19f, 1.25f)
        }
    }

    /** A ring with a handle: search. */
    val Search: ImageVector by lazy {
        shapes("Search", PathFillType.EvenOdd) {
            circle(10f, 10f, 7f)
            circle(10f, 10f, 4.75f)
            // Handle from the ring's lower right, 45 degrees.
            moveTo(14.2f, 15.8f)
            lineTo(15.8f, 14.2f)
            lineTo(21.2f, 19.6f)
            quadTo(21.7f, 20.4f, 21.2f, 21.2f)
            quadTo(20.4f, 21.7f, 19.6f, 21.2f)
            close()
        }
    }

    /** Two crossed bars: remove. */
    val Close: ImageVector by lazy {
        shapes("Close") {
            for ((a, b) in listOf(Pair(1f, 1f), Pair(1f, -1f))) {
                // A 2 wide bar from corner to corner of a 14 dp box centred on 12,12.
                val d = 0.71f
                moveTo(12f - 7f * a - d * b, 12f - 7f * b + d * a)
                lineTo(12f - 7f * a + d * b, 12f - 7f * b - d * a)
                lineTo(12f + 7f * a + d * b, 12f + 7f * b - d * a)
                lineTo(12f + 7f * a - d * b, 12f + 7f * b + d * a)
                close()
            }
        }
    }

    /** Three sliders: choose the fields shown. */
    val Fields: ImageVector by lazy {
        shapes("Fields") {
            for ((y, knob) in listOf(6f to 15f, 12f to 8f, 18f to 13f)) {
                roundRect(3f, y - 1f, 21f, y + 1f, 1f)
                roundRect(knob - 2.5f, y - 2.5f, knob + 2.5f, y + 2.5f, 2.5f)
            }
        }
    }
}

private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float = 2f) {
    moveTo(l + radius, t)
    lineTo(r - radius, t)
    quadTo(r, t, r, t + radius)
    lineTo(r, b - radius)
    quadTo(r, b, r - radius, b)
    lineTo(l + radius, b)
    quadTo(l, b, l, b - radius)
    lineTo(l, t + radius)
    quadTo(l, t, l + radius, t)
    close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
    arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
    close()
}

private fun shapes(name: String, fill: PathFillType = PathFillType.NonZero, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = fill, pathBuilder = block)
    }.build()

private fun symbol(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, viewportWidth = 960f, viewportHeight = 960f).apply {
        // Symbols use a 0,-960 viewBox.
        group(translationY = 960f) {
            addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        }
    }.build()
