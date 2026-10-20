package app.player.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Fallback scheme from docs/design.md: seed #FF8A5C, Tonal Spot, 2025 colour spec.
// Regenerate from the seed rather than editing single values.

internal val FallbackLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF8B4F38),
    onPrimary = Color(0xFFFFF7F5),
    primaryContainer = Color(0xFFFFB194),
    onPrimaryContainer = Color(0xFF63301B),
    secondary = Color(0xFF78584C),
    onSecondary = Color(0xFFFFF7F5),
    secondaryContainer = Color(0xFFFFDBCE),
    onSecondaryContainer = Color(0xFF694B40),
    tertiary = Color(0xFF735C25),
    onTertiary = Color(0xFFFFF8F0),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF644F19),
    error = Color(0xFFA83836),
    onError = Color(0xFFFFF7F6),
    errorContainer = Color(0xFFFA746F),
    onErrorContainer = Color(0xFF6E0A12),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF3E2F2A),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF3E2F2A),
    surfaceVariant = Color(0xFFF6DDD5),
    onSurfaceVariant = Color(0xFF6D5B55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EC),
    surfaceContainer = Color(0xFFFEEAE3),
    surfaceContainerHigh = Color(0xFFFAE4DD),
    surfaceContainerHighest = Color(0xFFF6DDD5),
    outline = Color(0xFF8A7770),
    outlineVariant = Color(0xFFC3ADA6),
    inverseSurface = Color(0xFF130D0A),
    inverseOnSurface = Color(0xFFA79A96),
    inversePrimary = Color(0xFFFFB194),
    scrim = Color(0xFF000000),
)

internal val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFF8B8A0),
    onPrimary = Color(0xFF603524),
    primaryContainer = Color(0xFF764634),
    onPrimaryContainer = Color(0xFFFFDCD0),
    secondary = Color(0xFFE7BEAF),
    onSecondary = Color(0xFF56392F),
    secondaryContainer = Color(0xFF50352B),
    onSecondaryContainer = Color(0xFFDFB6A8),
    tertiary = Color(0xFFFFEFD3),
    onTertiary = Color(0xFF6E5721),
    tertiaryContainer = Color(0xFFFFDF9B),
    onTertiaryContainer = Color(0xFF644F19),
    error = Color(0xFFFA746F),
    onError = Color(0xFF490006),
    errorContainer = Color(0xFF871F21),
    onErrorContainer = Color(0xFFFF9993),
    background = Color(0xFF130D0A),
    onBackground = Color(0xFFF9E0D8),
    surface = Color(0xFF130D0A),
    onSurface = Color(0xFFF9E0D8),
    surfaceVariant = Color(0xFF30231E),
    onSurfaceVariant = Color(0xFFBCA69F),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1A110E),
    surfaceContainer = Color(0xFF221714),
    surfaceContainerHigh = Color(0xFF281D19),
    surfaceContainerHighest = Color(0xFF30231E),
    outline = Color(0xFF84716B),
    outlineVariant = Color(0xFF55443F),
    inverseSurface = Color(0xFFFFF8F6),
    inverseOnSurface = Color(0xFF5D534F),
    inversePrimary = Color(0xFF845340),
    scrim = Color(0xFF000000),
)

/** OLED toggle. Higher containers keep their tone so cards and sheets still separate. */
internal fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
)

/** Player chrome. Fixed, never themed: it sits over video. */
object PlayerColors {
    val Scrim = Color.Black.copy(alpha = 0.6f)
    val Content = Color.White
    val ContentSecondary = Color.White.copy(alpha = 0.7f)
    val ContentDisabled = Color.White.copy(alpha = 0.38f)
    val Track = Color.White.copy(alpha = 0.3f)
    val Buffered = Color.White.copy(alpha = 0.5f)
}
