package app.player.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.player.core.designsystem.theme.LanthornTheme
import app.player.core.designsystem.theme.ThemeMode
import app.player.core.testing.Ears
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * US-69.4: text on controls at 4.5:1 or more, in every scheme the app can draw on this device:
 * the fallback colours, the wallpaper (dynamic) colours, light, dark and pure black.
 */
@RunWith(AndroidJUnit4::class)
class ContrastOnDeviceTest {
    @get:Rule val rule = createComposeRule()

    private fun ratio(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** Text colour on the control colour it sits on: buttons, chips, toolbars, list rows, switches' labels. */
    private fun pairs(s: ColorScheme) = mapOf(
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onError/error" to (s.onError to s.error),
        "primary/surface (text buttons)" to (s.primary to s.surface),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurface/surfaceContainer (toolbar)" to (s.onSurface to s.surfaceContainer),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
    )

    @Ears("US-69.4")
    @Test fun us_69_4_text_on_controls_meets_4_5_to_1_in_every_scheme() {
        val schemes = linkedMapOf<String, ColorScheme>()
        rule.setContent {
            Column {
                for (dynamic in listOf(false, true)) {
                    LanthornTheme(ThemeMode.Light, dynamicColor = dynamic) { schemes["light dynamic=$dynamic"] = MaterialTheme.colorScheme }
                    LanthornTheme(ThemeMode.Dark, dynamicColor = dynamic) { schemes["dark dynamic=$dynamic"] = MaterialTheme.colorScheme }
                    LanthornTheme(ThemeMode.Dark, dynamicColor = dynamic, pureBlack = true) { schemes["black dynamic=$dynamic"] = MaterialTheme.colorScheme }
                }
            }
        }
        rule.waitForIdle()
        assertTrue(schemes.size == 6)
        val low = schemes.flatMap { (name, s) ->
            pairs(s).mapNotNull { (pair, c) -> ratio(c.first, c.second).takeIf { it < 4.5f }?.let { "$name $pair ${"%.2f".format(it)}" } }
        }
        assertTrue("under 4.5:1: $low", low.isEmpty())
    }
}
