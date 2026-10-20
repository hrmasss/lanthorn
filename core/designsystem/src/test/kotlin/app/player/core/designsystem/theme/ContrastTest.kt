package app.player.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2 contrast for the fallback scheme and the overlay (docs/design.md, Accessibility). */
class ContrastTest {

    private fun ratio(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun pairs(s: ColorScheme) = mapOf(
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onError/error" to (s.onError to s.error),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurfaceVariant/surface" to (s.onSurfaceVariant to s.surface),
        "onSurfaceVariant/surfaceContainerHighest" to (s.onSurfaceVariant to s.surfaceContainerHighest),
    )

    private fun assertScheme(name: String, s: ColorScheme) {
        for ((pair, colors) in pairs(s)) {
            val r = ratio(colors.first, colors.second)
            assertTrue("$name $pair is $r, needs 4.5", r >= 4.5f)
        }
    }

    @Test fun fallbackLightMeetsAA() = assertScheme("light", FallbackLight)

    @Test fun fallbackDarkMeetsAA() = assertScheme("dark", FallbackDark)

    @Test fun pureBlackMeetsAA() = assertScheme("pure black", FallbackDark.pureBlack())

    @Test fun overlayTextReadableOverWhiteFrame() {
        val behindText = PlayerColors.Scrim.compositeOver(Color.White)
        assertTrue(ratio(PlayerColors.Content, behindText) >= 4.5f)
    }

    @Test fun overlaySecondaryTextReadableOverMidGreyFrame() {
        val behindText = PlayerColors.Scrim.compositeOver(Color(0xFF808080))
        val text = PlayerColors.ContentSecondary.compositeOver(behindText)
        assertTrue(ratio(text, behindText) >= 4.5f)
    }
}
