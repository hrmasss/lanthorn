package app.player.feature.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

// Material Symbols Rounded, weight 400, 24 px. Copyright Google, Apache License 2.0
// (https://github.com/google/material-design-icons). Glyphs the design system lacks; proposed to @brand.

internal object LibraryIcons {
    val Back: ImageVector by lazy { symbol("Back", "m313-440 196 196q12 12 11.5 28T508-188q-12 11-28 11.5T452-188L188-452q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l264-264q11-11 27.5-11t28.5 11q12 12 12 28.5T508-715L313-520h447q17 0 28.5 11.5T800-480q0 17-11.5 28.5T760-440H313Z") }
    val Folder: ImageVector by lazy { symbol("Folder", "M160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h207q16 0 30.5 6t25.5 17l57 57h320q33 0 56.5 23.5T880-640v400q0 33-23.5 56.5T800-160H160Z") }
}

private fun symbol(name: String, pathData: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, viewportWidth = 960f, viewportHeight = 960f).apply {
        // Symbols use a 0,-960 viewBox.
        group(translationY = 960f) {
            addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        }
    }.build()
