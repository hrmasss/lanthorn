package app.player.core.designsystem.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon

/**
 * The brand's one signature shape, the launcher icon's 9-sided cookie. Use it in the centre
 * play button and the empty library only, so it stays special.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val BrandPolygon: RoundedPolygon
    get() = MaterialShapes.Cookie9Sided
