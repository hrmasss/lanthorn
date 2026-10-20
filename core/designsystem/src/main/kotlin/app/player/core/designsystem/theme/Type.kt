package app.player.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

// System font throughout (docs/design.md, Type). Only additions to the M3 defaults live here.
internal val AppTypography = Typography()

/** Tabular figures so timecodes do not jitter while they count. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
